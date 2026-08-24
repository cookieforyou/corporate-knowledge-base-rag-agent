package com.enterprise.kb.eval.runner;

import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.ai.advisor.SemanticInjectionAdvisor;
import com.enterprise.kb.ai.prompt.PromptTemplates;
import com.enterprise.kb.eval.config.EvalProperties;
import com.enterprise.kb.eval.dataset.AttackType;
import com.enterprise.kb.eval.dataset.GoldenQAPair;
import com.enterprise.kb.eval.dataset.GoldenDatasetLoader;
import com.enterprise.kb.eval.dataset.QACategory;
import com.enterprise.kb.eval.metric.CitationMetrics;
import com.enterprise.kb.eval.metric.JudgePrompts;
import com.enterprise.kb.eval.metric.RetrievalMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评估执行器（设计文档 16.5）
 *
 * <p>流程：Golden 用例 → 检索探针取数 → 检索指标 → 被测链路生成回答 → Judge 评分 →
 * 聚合 EvalReport → CI 门禁（ci profile 启用时）。
 *
 * <p>被测链路注入 kb-ai-core 的 {@code chatClient}（Phase 1 形态为 QuestionAnswerAdvisor；
 * Phase 2.10 替换为 RetrievalAugmentationAdvisor 后，本执行器无需改动即可度量新链路）。
 */
@Slf4j
@Component
public class EvalRunner {

    private final GoldenDatasetLoader datasetLoader;
    private final RetrievalProbe retrievalProbe;
    private final ChatClient chatClient;        // 被测链路
    private final ChatModel chatModel;          // 被测基座（Noise Robustness 评估侧生成，簇② 5.8）
    private final ChatClient judgeChatClient;   // Judge（跨厂商，16.3）
    private final ChatClient guardrailChatClient; // INJECTION 专属护栏链（簇⑤ B2 S6）
    private final ChatClient guardrailL2ChatClient; // INJECTION L1+L2 联合护栏链（安全簇⑤ E2）
    private final IndirectInjectionRunner indirectInjectionRunner; // 间接注入评估（簇④ D3）
    private final EvalProperties props;
    private final JsonMapper jsonMapper;   // 机读快照序列化（簇② 5.9 批3，Jackson 3 命名空间，坑位⑬）
    private final ApplicationArguments args;

    public EvalRunner(GoldenDatasetLoader datasetLoader,
                      List<RetrievalProbe> probes,
                      @Qualifier("chatClient") ChatClient chatClient,
                      ChatModel chatModel,
                      @Qualifier("judgeChatClient") ChatClient judgeChatClient,
                      @Qualifier("evalGuardrailChatClient") ChatClient guardrailChatClient,
                      @Qualifier("evalGuardrailL2ChatClient") ChatClient guardrailL2ChatClient,
                      IndirectInjectionRunner indirectInjectionRunner,
                      EvalProperties props,
                      JsonMapper jsonMapper,
                      ApplicationArguments args) {
        this.datasetLoader = datasetLoader;
        // 探针选择：auto = order 最小者胜出（混合探针 order=0 自动替代单路基线 order=100）；
        //          vector/hybrid = 显式指定，用于 A/B 基线对比
        this.retrievalProbe = selectProbe(probes, props.getProbe());
        this.chatClient = chatClient;
        this.chatModel = chatModel;
        this.judgeChatClient = judgeChatClient;
        this.guardrailChatClient = guardrailChatClient;
        this.guardrailL2ChatClient = guardrailL2ChatClient;
        this.indirectInjectionRunner = indirectInjectionRunner;
        this.props = props;
        this.jsonMapper = jsonMapper;
        this.args = args;
    }

    /**
     * 启动即跑（README §4.2 模式①②）：
     *
     * <ul>
     *   <li>手动模式（默认）：全量评估 + 报告双通道发布，不做门禁判定；</li>
     *   <li>ci profile：同上，且低于阈值抛出 {@link EvalFailedException}（进程非零退出）；</li>
     *   <li>标注辅助模式（--eval.annotate-query / --eval.annotate-all）：仅由
     *       AnnotationRunner 输出候选 Chunk（单条/全量重标注表），不跑全量评估——
     *       避免标注问题白烧一整轮模型调用。</li>
     * </ul>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        boolean ci = props.getCi().isEnabled();
        // 工具模式不跑全量评估：标注辅助（簇④ A4）/ expectedAnswer 草稿与 κ 回读
        // （簇② 批2）/ A/B 差异报表（簇② 批3，纯快照消费零计费）——
        // 避免工具性启动白烧一整轮模型调用
        if (!ci && (args.containsOption("eval.annotate-query") || args.containsOption("eval.annotate-all")
                || args.containsOption("eval.draft-answers") || args.containsOption("eval.calibration-readback")
                || args.containsOption("eval.diff"))) {
            return;
        }
        // 前置快失败：Judge 密钥缺失时所有生成侧评分必然失败，不允许静默「通过」
        // （检索-only 模式不触发 Judge，豁免此检查）
        if (!props.isRetrievalOnly()
                && (props.getJudge().getApiKey() == null || props.getJudge().getApiKey().isBlank())) {
            if (ci) {
                throw new EvalFailedException("DASHSCOPE_API_KEY 未配置，Judge 不可用——门禁拒绝运行");
            }
            log.warn("DASHSCOPE_API_KEY 未配置，跳过评估（Judge 不可用）");
            return;
        }
        EvalReport report = runFullEval();
        publishReport(report);
        writeJudgeAgreementSheetIfNeeded(report);
        // 间接注入评估（安全簇④ D3）：独立报告面（target/indirect-eval{-label}），
        // 首跑基线入档无门禁阈值；总开关关/语料空静默跳过，评估失败不静默
        indirectInjectionRunner.runIfNeeded();
        if (ci) {
            report.assertThresholds(props);
            log.info("✅ 评估门禁通过");
        }
    }

    /**
     * 报告双通道发布：① stdout 直出（不依赖日志配置，CI 日志必可见）；
     * ② 落盘 target/eval-report{-label}.txt（本地可复查的历史产物；run-label 非空时
     * 文件名带标签——簇④ E1 校准复跑与 A/B 快照各留独立文件，避免互覆）。
     *
     * <p>簇② 5.9 批3：报告头加运行锚点（git hash + 提交时间 + 运行时刻），
     * 并同步落盘机读快照 target/eval-results{-label}.json（A/B diff 数据面，
     * {@code --eval.diff} 消费）。
     */
    private void publishReport(EvalReport report) {
        GitAnchor anchor = GitAnchor.resolve();
        String summary = report.summary();
        if (props.isRetrievalOnly()) {
            summary = "【检索-only 模式】生成侧与 Judge 已跳过，仅检索侧指标有效"
                + System.lineSeparator() + summary;
        }
        String header = renderAnchorHeader(anchor, props.getRunLabel());
        System.out.println(header + summary);
        log.info("\n{}", header + summary);
        try {
            String label = props.getRunLabel() == null ? "" : props.getRunLabel().trim();
            String fileName = label.isEmpty() ? "eval-report.txt" : "eval-report-" + label + ".txt";
            java.nio.file.Path out = java.nio.file.Path.of("target", fileName);
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, header + summary + System.lineSeparator());
            log.info("评估报告已写入: {}", out.toAbsolutePath());
        } catch (Exception e) {
            log.warn("评估报告落盘失败（不影响门禁）: {}", e.getMessage());
        }
        writeSnapshot(report, anchor);
    }

    /**
     * 运行锚点头（簇② 5.9 批3）：git 提交 + 工作区状态 + 运行时刻——
     * A/B 双跑差异归因的代码形态回溯依据（Prompt Git Ops 4.8：prompt 版本即
     * git 版本）。工作区脏时哈希不能完全代表运行代码，显式 ⚠。
     */
    static String renderAnchorHeader(GitAnchor anchor, String runLabel) {
        String ls = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append("── 运行锚点 ──").append(ls);
        sb.append("运行标签:  ")
            .append(runLabel == null || runLabel.isBlank() ? "（无）" : runLabel).append(ls);
        if (anchor.resolved()) {
            sb.append("git 提交:  ").append(anchor.commitShort()).append("（").append(anchor.commit()).append("）")
                .append(ls);
            sb.append("提交时间:  ").append(anchor.commitTime()).append("；工作区: ")
                .append(anchor.dirty() ? "脏 ⚠（含未提交改动，锚点不完全代表代码形态）" : "干净").append(ls);
        } else {
            sb.append("git 提交:  ").append(GitAnchor.UNKNOWN).append("（非 git 工作区或 git 不可用）").append(ls);
        }
        sb.append("运行时刻:  ").append(anchor.runAt()).append(ls);
        return sb.toString();
    }

    /**
     * 机读快照落盘（簇② 5.9 批3）：target/eval-results{-label}.json——
     * 锚点 + 运行配置 + 聚合 + 逐用例读数（内容盲，见 {@link EvalSnapshot}）。
     * 落盘失败不阻断评估（与报告落盘同容错等级）。
     */
    private void writeSnapshot(EvalReport report, GitAnchor anchor) {
        try {
            EvalSnapshot snapshot = EvalSnapshot.from(report, props, anchor);
            String label = props.getRunLabel() == null ? "" : props.getRunLabel().trim();
            String fileName = label.isEmpty() ? "eval-results.json" : "eval-results-" + label + ".json";
            java.nio.file.Path out = java.nio.file.Path.of("target", fileName);
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out,
                jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot)
                    + System.lineSeparator());
            log.info("评估机读快照已写入: {}（A/B 差异报表经 --eval.diff 消费）", out.toAbsolutePath());
        } catch (Exception e) {
            log.warn("评估机读快照落盘失败（不影响评估）: {}", e.getMessage());
        }
    }

    /**
     * 人类校准抽样表（簇④ E1 建基，簇② 批2 扩为五维校准通道；
     * judge-agreement-sample > 0 时启用）：全量评估后按分类分层抽 N 条正向用例，
     * 落盘双通道——
     * <ul>
     *   <li>{@code target/judge-agreement-sheet.md}：打分材料（问题/参考资料/回答/
     *       各维 Judge 值与理由；NRob 含答案 B），供标注人阅读；</li>
     *   <li>{@code target/judge-agreement-sheet.csv}：打分表（长表，每行 = 用例×维度，
     *       human_a/human_b 双标注列），人工填写后经 --eval.calibration-readback
     *       回读计算 Cohen's κ（目标 ≥0.80，观察带接入门禁的前置判据）。</li>
     * </ul>
     * 检索-only 模式无意义，跳过。
     */
    private void writeJudgeAgreementSheetIfNeeded(EvalReport report) {
        int n = props.getJudgeAgreementSample();
        if (n <= 0 || props.isRetrievalOnly()) {
            return;
        }
        List<EvalResult> generation = report.results().stream()
            .filter(r -> !r.isNegative() && r.faithfulness() != null).toList();
        if (generation.isEmpty()) {
            log.warn("校准抽样跳过：无生成侧评估结果");
            return;
        }
        List<EvalResult> sampled = stratifiedSample(generation, n, 42L);
        try {
            java.nio.file.Path outDir = java.nio.file.Path.of("target");
            java.nio.file.Files.createDirectories(outDir);
            java.nio.file.Files.writeString(outDir.resolve("judge-agreement-sheet.md"),
                renderAgreementSheet(sampled));
            java.nio.file.Files.writeString(outDir.resolve("judge-agreement-sheet.csv"),
                renderCalibrationCsv(sampled));
            log.info("人类校准抽样表（{} 条，MD 材料 + CSV 打分表）已写入: {}",
                sampled.size(), outDir.toAbsolutePath());
        } catch (Exception e) {
            log.warn("校准抽样表落盘失败（不影响评估）: {}", e.getMessage());
        }
    }

    /**
     * 分类分层抽样：各分类配额按样本占比分配（整数部分落位后，余位按小数部分降序补位），
     * 类内固定种子洗牌——复跑抽到同一批用例，人工打分可纵向对比。配额超出分类样本数时取全量。
     */
    static List<EvalResult> stratifiedSample(List<EvalResult> generation, int n, long seed) {
        Map<QACategory, List<EvalResult>> byCategory = generation.stream()
            .collect(Collectors.groupingBy(r -> r.pair().category(),
                java.util.LinkedHashMap::new, Collectors.toList()));
        java.util.Random rnd = new java.util.Random(seed);
        byCategory.values().forEach(list -> java.util.Collections.shuffle(list, rnd));

        int total = generation.size();
        n = Math.min(n, total);
        List<Map.Entry<QACategory, List<EvalResult>>> entries = new ArrayList<>(byCategory.entrySet());
        int[] quotas = new int[entries.size()];
        double[] fractions = new double[entries.size()];
        int assigned = 0;
        for (int i = 0; i < entries.size(); i++) {
            double raw = (double) n * entries.get(i).getValue().size() / total;
            quotas[i] = (int) raw;
            fractions[i] = raw - quotas[i];
            assigned += quotas[i];
        }
        Integer[] byFraction = new Integer[entries.size()];
        for (int i = 0; i < byFraction.length; i++) byFraction[i] = i;
        java.util.Arrays.sort(byFraction, (a, b) -> Double.compare(fractions[b], fractions[a]));
        for (int idx : byFraction) {
            if (assigned >= n) break;
            quotas[idx]++;
            assigned++;
        }
        List<EvalResult> sampled = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            int quota = Math.min(quotas[i], entries.get(i).getValue().size());
            sampled.addAll(entries.get(i).getValue().subList(0, quota));
        }
        return sampled;
    }

    /**
     * 校准表材料渲染（阅读面）。五维打分口径随头部说明下发；分数填写在配套
     * CSV（human_a/human_b 双列），本文件只提供判定所需材料。
     */
    String renderAgreementSheet(List<EvalResult> sampled) {
        EvalProperties.Judge j = props.getJudge();
        StringBuilder sb = new StringBuilder();
        sb.append("# 人类校准打分材料（簇② 5.8 批2）").append(System.lineSeparator());
        sb.append(System.lineSeparator());
        sb.append(String.format("- Judge: %s（temperature=%.1f, enable_thinking=%s）%n",
            j.getModel(), j.getTemperature(), j.isEnableThinking()));
        sb.append(String.format("- 运行标签: %s%n",
            props.getRunLabel() == null || props.getRunLabel().isBlank() ? "（无）" : props.getRunLabel()));
        sb.append("- 填写通道：配套 `judge-agreement-sheet.csv` 的 human_a / human_b 列")
            .append("（两位标注人独立填写，互不参照）").append(System.lineSeparator());
        sb.append("- 校准判据：逐维 Cohen's κ ≥ 0.80（回读命令 `--eval.calibration-readback=<csv>`）")
            .append(System.lineSeparator());
        sb.append(System.lineSeparator()).append("## 五维打分口径").append(System.lineSeparator());
        sb.append(System.lineSeparator());
        sb.append("| 维度（CSV dimension 列） | 人工填写 | 口径 |").append(System.lineSeparator());
        sb.append("|---|---|---|").append(System.lineSeparator());
        sb.append("| faithfulness | 1-5 整数 | 回答对参考资料的忠实度（同 E1 口径） |").append(System.lineSeparator());
        sb.append("| answer_correctness | 1-5 整数 | 对照理想回答的事实正确性（仅理想回答已标注用例） |").append(System.lineSeparator());
        sb.append("| citation_attribution | SUPPORTED / NOT_SUPPORTED | 回答中每个 [ref-N] 标注是否被对应编号资料支撑；回答未发出任何引用 → NOT_SUPPORTED |").append(System.lineSeparator());
        sb.append("| hallucination | YES / NO | 回答是否含参考资料无依据的事实声明（≥1 条即 YES） |").append(System.lineSeparator());
        sb.append("| noise_robustness | CONSISTENT / DRIFTED | 对照答案 A / 答案 B 的事实结论是否一致（仅噪声抽样用例） |").append(System.lineSeparator());
        sb.append(System.lineSeparator()).append("---").append(System.lineSeparator());
        for (int i = 0; i < sampled.size(); i++) {
            EvalResult r = sampled.get(i);
            String context = r.hits() == null ? "" : r.hits().stream()
                .map(h -> "[%s] %s".formatted(h.chunkId(), truncate(h.content(), 800)))
                .collect(Collectors.joining("\n\n"));
            sb.append(String.format("%n## %d. %s（%s）%n%n", i + 1, r.pair().id(), r.pair().category()));
            sb.append("**问题**：").append(r.pair().question()).append(System.lineSeparator());
            sb.append(System.lineSeparator()).append("**参考资料**（Judge 所见，[ref-N] 对应编号）：")
                .append(System.lineSeparator());
            sb.append(System.lineSeparator()).append(context).append(System.lineSeparator());
            sb.append(System.lineSeparator()).append("**模型回答（答案 A）**：").append(System.lineSeparator());
            sb.append(System.lineSeparator()).append(r.answer() == null ? "（生成失败）" : r.answer())
                .append(System.lineSeparator());
            sb.append(System.lineSeparator()).append("**Judge 读数**：").append(System.lineSeparator());
            sb.append(System.lineSeparator())
                .append(String.format("- Faithfulness = %.0f（理由：%s）%n",
                    r.faithfulness(), r.judgeReason() == null ? "无" : r.judgeReason()));
            if (r.pair().expectedAnswer() != null && !r.pair().expectedAnswer().isBlank()) {
                sb.append(System.lineSeparator()).append("**理想回答**（AC 打分对照）：")
                    .append(System.lineSeparator()).append(System.lineSeparator())
                    .append(r.pair().expectedAnswer()).append(System.lineSeparator());
                sb.append(System.lineSeparator()).append(String.format("- Answer Correctness = %.0f%n",
                    r.answerCorrectness() == null ? Double.NaN : r.answerCorrectness()));
            }
            if (r.citationVerdict() != null) {
                sb.append(String.format("- Citation Attribution = %s（可解析率 %s）%n",
                    r.citationVerdict(),
                    r.citationResolvableRate() == null ? "—"
                        : String.format(java.util.Locale.ROOT, "%.2f", r.citationResolvableRate())));
            }
            if (r.hallucinationRate() != null) {
                sb.append(String.format(java.util.Locale.ROOT, "- Hallucination Rate = %.1f%%%n",
                    r.hallucinationRate() * 100));
            }
            if (r.noiseVerdict() != null) {
                sb.append(String.format("- Noise Robustness = %s%n", r.noiseVerdict()));
                sb.append(System.lineSeparator()).append("**答案 B（混噪生成，NRob 对照）**：")
                    .append(System.lineSeparator()).append(System.lineSeparator())
                    .append(r.noiseAnswer() == null ? "（无）" : r.noiseAnswer())
                    .append(System.lineSeparator());
            }
        }
        return sb.toString();
    }

    /**
     * 校准打分表渲染（填写面，簇② 批2）：长表，每行 = 用例 × 维度，
     * 列 = case_id,category,dimension,judge_value,human_a,human_b。
     * 维度行只在 Judge 产出该维读数时生成；HR 的 judge_value 为原始比率
     * （回读层二值化：>0 → YES）；CA 原样携带 NO_CITATION（回读层归并为
     * NOT_SUPPORTED——与聚合语义一致，未发出引用判负）。
     */
    static String renderCalibrationCsv(List<EvalResult> sampled) {
        StringBuilder sb = new StringBuilder("case_id,category,dimension,judge_value,human_a,human_b\n");
        for (EvalResult r : sampled) {
            String id = r.pair().id();
            String category = r.pair().category().name();
            if (r.faithfulness() != null) {
                sb.append(csvRow(id, category, "faithfulness",
                    String.format(java.util.Locale.ROOT, "%.0f", r.faithfulness())));
            }
            if (r.answerCorrectness() != null) {
                sb.append(csvRow(id, category, "answer_correctness",
                    String.format(java.util.Locale.ROOT, "%.0f", r.answerCorrectness())));
            }
            if (r.citationVerdict() != null) {
                sb.append(csvRow(id, category, "citation_attribution", r.citationVerdict()));
            }
            if (r.hallucinationRate() != null) {
                sb.append(csvRow(id, category, "hallucination",
                    String.format(java.util.Locale.ROOT, "%s", r.hallucinationRate())));
            }
            if (r.noiseVerdict() != null) {
                sb.append(csvRow(id, category, "noise_robustness", r.noiseVerdict()));
            }
        }
        return sb.toString();
    }

    private static String csvRow(String id, String category, String dimension, String judgeValue) {
        return id + "," + category + "," + dimension + "," + judgeValue + ",,\n";
    }

    public EvalReport runFullEval() {
        List<GoldenQAPair> dataset = sample(datasetLoader.loadAll());
        if (dataset.isEmpty()) {
            throw new EvalFailedException("Golden Dataset 为空（classpath:golden/*.json 无可用用例）");
        }
        int concurrency = Math.max(1, props.getConcurrency());
        log.info("开始评估：{} 条用例，检索探针 = {}，并行度 = {}{}", dataset.size(),
            retrievalProbe.name(), concurrency, props.isRetrievalOnly() ? "（检索-only 模式）" : "");

        warmupRetrieval();

        // Noise Robustness 抽样映射（簇② 5.8）：正向用例按数据集顺序取前 N 条，
        // 噪声问句 = 数据集内下一条用例的问题（循环取，确定性复跑可对照）
        Map<String, String> noiseQueries = noiseQueryMap(dataset);

        // 并行执行（2026-08-03 提速）：单用例 = 检索 + 1 次生成 + 至多 2 次 Judge 的串联 LLM 调用，
        // 纯 IO 等待、用例间无共享状态（探针/ChatClient 均线程安全），并发后时延约 1/N。
        // Semaphore 限流 concurrency 个在飞用例（虚拟线程无上限，不限流会击穿 LLM API 速率限制）；
        // concurrency=1 退化为串行。结果按数据集顺序收集，报告逐用例明细顺序不变。
        List<EvalResult> results = new ArrayList<>();
        var inflight = new java.util.concurrent.Semaphore(concurrency);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = dataset.stream()
                .map(pair -> executor.submit(() -> {
                    inflight.acquire();
                    try {
                        return evaluateOne(pair, noiseQueries.get(pair.id()));
                    } finally {
                        inflight.release();
                    }
                }))
                .toList();
            for (int i = 0; i < futures.size(); i++) {
                GoldenQAPair pair = dataset.get(i);
                try {
                    results.add(futures.get(i).get());
                    log.info("[{}/{}] {} ✓", i + 1, dataset.size(), pair.id());
                } catch (Exception e) {
                    log.error("[{}/{}] {} 评估失败: {}", i + 1, dataset.size(), pair.id(),
                        e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                }
            }
        }
        // 全部用例失败（基础设施不可达/密钥错误等）不得静默「通过」
        if (results.isEmpty()) {
            throw new EvalFailedException(
                "无有效评估结果（全部 " + dataset.size() + " 条用例失败）——请检查 ECS 基础设施连通性与 API Keys");
        }
        return aggregate(retrievalProbe.name(), results);
    }

    /**
     * 检索链路预热（2026-08-04）：Milvus gRPC 首次调用需建连 + 加载 collection，
     * 耗时超过检索路 5s 超时——并行评估时首批用例集体命中冷启动、向量路降级空结果，
     * 污染首批用例 Recall（实测 15 次超时）。一次性探针查询吸收建连成本，失败不阻断。
     */
    private void warmupRetrieval() {
        try {
            retrievalProbe.probe("预热查询 warmup", 1);
            log.info("检索链路预热完成");
        } catch (Exception e) {
            log.warn("检索链路预热失败（不阻断评估）: {}", e.getMessage());
        }
    }

    private EvalResult evaluateOne(GoldenQAPair pair, String noiseQuery) {
        // 0. 注入攻击用例（簇⑤ B2 S6）：确定性判定，零 Judge 零检索——走 eval 专属
        //    护栏链（仅 InputSanitizeAdvisor，无配额/审计 Advisor，免 429 污染与审计噪声）；
        //    捕获 PROMPT_INJECTION → BLOCKED，正常返回 → NOT_BLOCKED（答案丢弃）
        if (pair.isInjection()) {
            return evaluateInjection(pair);
        }

        // 1. 检索取数
        List<RetrievalProbe.ProbeHit> hits = retrievalProbe.probe(pair.question(), props.getTopK());
        List<String> hitIds = hits.stream().map(RetrievalProbe.ProbeHit::chunkId).toList();

        // 2. 检索指标（无期望标注 → NaN，聚合时跳过）
        double recall = RetrievalMetrics.recallAtK(hitIds, pair.expectedChunkIds());
        double mrr = RetrievalMetrics.reciprocalRank(hitIds, pair.expectedChunkIds());
        double precision = RetrievalMetrics.contextPrecision(hitIds, pair.expectedChunkIds());

        // 2b. 文档级兜底指标（簇④ A4 修复）：file_name 匹配，跨重入库恒稳定——
        // chunk ID 失配（重入库换代/解析漂移）时的方向性度量；无 expectedDocs → NaN
        List<String> hitFileNames = hits.stream()
            .map(RetrievalProbe.ProbeHit::fileName)
            .filter(java.util.Objects::nonNull)
            .toList();
        double docRecall = RetrievalMetrics.recallAtK(hitFileNames, pair.expectedDocs());
        double docMrr = RetrievalMetrics.reciprocalRank(hitFileNames, pair.expectedDocs());
        double docPrecision = RetrievalMetrics.contextPrecision(hitFileNames, pair.expectedDocs());

        // 检索-only 模式：到此为止——跳过被测生成与 Judge（生成侧指标 null，聚合自动跳过；
        // 负向用例无生成即无拒答判定，同样跳过）。语料标注核验/检索回归的秒级通道。
        if (props.isRetrievalOnly()) {
            return new EvalResult(pair, hits, null, recall, mrr, precision,
                docRecall, docMrr, docPrecision, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        }

        // 3. 被测链路生成
        String answer = chatClient.prompt().user(pair.question()).call().content();

        // 4. Judge 评分
        if (pair.isNegative()) {
            JudgePrompts.JudgeScore js = judge(String.format(
                JudgePrompts.NEGATIVE_REJECTION, pair.question(), answer));
            return new EvalResult(pair, hits, answer, recall, mrr, precision,
                docRecall, docMrr, docPrecision, null, null, js.verdict(), scoreOf(js), js.reason(), null, null,
                null, null, null, null, null, null);
        }
        String context = hits.stream()
            .map(h -> "[%s] %s".formatted(h.chunkId(), truncate(h.content(), 800)))
            .collect(Collectors.joining("\n\n"));
        JudgePrompts.JudgeScore faithfulness = judge(String.format(
            JudgePrompts.FAITHFULNESS, pair.question(), context, answer));
        JudgePrompts.JudgeScore relevancy = judge(String.format(
            JudgePrompts.RESPONSE_RELEVANCY, pair.question(), answer));

        // 5. Phase 5 扩展指标（簇② 5.8）——观察带；总开关关时全 null（聚合自动跳过）
        Double answerCorrectness = null;
        String citationVerdict = null;
        Double citationResolvableRate = null;
        Double hallucinationRate = null;
        String noiseVerdict = null;
        if (props.getMetrics().isPhase5Enabled() && answer != null && !answer.isBlank()) {
            // Answer Correctness：expectedAnswer 标注用例（当前语料零标注 → 全跳过）
            if (pair.expectedAnswer() != null && !pair.expectedAnswer().isBlank()) {
                JudgePrompts.JudgeScore ac = judge(String.format(
                    JudgePrompts.ANSWER_CORRECTNESS, pair.question(), pair.expectedAnswer(), answer));
                answerCorrectness = scoreOf(ac);
            }
            // Citation Attribution：①发出 ②可解析（确定性）③来源支撑（Judge）
            var citation = judgeCitationAttribution(pair.question(), context, answer, hits.size());
            citationVerdict = citation.verdict();
            citationResolvableRate = citation.resolvableRate();
            // Hallucination Rate：声明级核查（score 0-100 → 0-1 比率）
            JudgePrompts.JudgeScore hr = judge(String.format(
                JudgePrompts.HALLUCINATION_RATE, pair.question(), context, answer));
            hallucinationRate = hr == null || hr.score() == null
                ? null : Math.clamp(hr.score(), 0, 100) / 100.0;
        }
        // Noise Robustness：噪声抽样用例（正常生成成功且扩展开关开时）
        String noiseAnswer = null;
        if (noiseQuery != null && answer != null && !answer.isBlank()) {
            NoiseOutcome noise = judgeNoiseRobustness(pair.question(), hits, answer, noiseQuery);
            if (noise != null) {
                noiseVerdict = noise.verdict();
                noiseAnswer = noise.noisyAnswer();
            }
        }

        return new EvalResult(pair, hits, answer, recall, mrr, precision,
            docRecall, docMrr, docPrecision,
            scoreOf(faithfulness), scoreOf(relevancy), null, null, faithfulness.reason(), null, null,
            answerCorrectness, citationVerdict, citationResolvableRate, hallucinationRate, noiseVerdict,
            noiseAnswer);
    }

    /**
     * Citation Attribution 三步判定（簇② 5.8，16 章 §16.2）。
     * ① 未发出引用 → NO_CITATION（免 Judge）；② 存在编号越界/失配 → NOT_SUPPORTED（确定性，
     * 免 Judge）；③ 前两步通过才进 Judge 判来源支撑。省 Judge 调用 = 观察带成本控制。
     */
    private CitationOutcome judgeCitationAttribution(String question, String context, String answer, int contextSize) {
        List<Integer> refs = CitationMetrics.extractRefs(answer);
        if (refs.isEmpty()) {
            return new CitationOutcome(CitationMetrics.VERDICT_NO_CITATION, null);
        }
        double resolvableRate = CitationMetrics.resolvableRate(refs, contextSize);
        if (resolvableRate < 1.0) {
            return new CitationOutcome(CitationMetrics.VERDICT_NOT_SUPPORTED, resolvableRate);
        }
        JudgePrompts.JudgeScore js = judge(String.format(
            JudgePrompts.CITATION_ATTRIBUTION, question, context, answer));
        String verdict = js != null && CitationMetrics.VERDICT_SUPPORTED.equalsIgnoreCase(js.verdict())
            ? CitationMetrics.VERDICT_SUPPORTED : CitationMetrics.VERDICT_NOT_SUPPORTED;
        return new CitationOutcome(verdict, resolvableRate);
    }

    /** 三步判定中间态（确定性阶段读数 + 最终 verdict） */
    private record CitationOutcome(String verdict, Double resolvableRate) {}

    /**
     * Noise Robustness 对照（簇② 5.8，16 章 §16.2）：噪声问句检索一批无关证据，
     * 与原证据编号续接混排（[ref-N] 契约与被测链路一致），经评估侧生成路径
     * （同一基座 + 同一 GROUNDING_PROMPT，仅上下文不同）产出回答 B，
     * Judge 判定 A/B 事实结论一致性。噪声证据不可用（检索空/全与原证据重叠）返回 null。
     * 答案 B 随判定一并回收（簇② 批2）——校准表人审 NRob 需对照 A/B 两答案。
     */
    private NoiseOutcome judgeNoiseRobustness(String question, List<RetrievalProbe.ProbeHit> baseHits,
                                              String baseAnswer, String noiseQuery) {
        List<RetrievalProbe.ProbeHit> noiseHits = retrievalProbe.probe(noiseQuery, props.getTopK());
        List<RetrievalProbe.ProbeHit> base = baseHits == null ? List.of() : baseHits;
        java.util.Set<String> baseIds = base.stream()
            .map(RetrievalProbe.ProbeHit::chunkId).collect(Collectors.toSet());
        List<RetrievalProbe.ProbeHit> disjoint = noiseHits.stream()
            .filter(h -> !baseIds.contains(h.chunkId())).toList();
        if (disjoint.isEmpty()) {
            return null;
        }
        String noisyContext = numberedContext(base, disjoint);
        String prompt = PromptTemplates.GROUNDING_PROMPT
            .replace("{context}", noisyContext)
            .replace("{query}", question);
        String noisyAnswer = chatModel.call(new Prompt(prompt)).getResult().getOutput().getText();
        if (noisyAnswer == null || noisyAnswer.isBlank()) {
            return null;
        }
        JudgePrompts.JudgeScore js = judge(String.format(
            JudgePrompts.NOISE_ROBUSTNESS, question, baseAnswer, noisyAnswer));
        if (js == null) {
            return null;
        }
        return new NoiseOutcome("CONSISTENT".equalsIgnoreCase(js.verdict()) ? "CONSISTENT" : "DRIFTED",
            noisyAnswer);
    }

    /** Noise Robustness 判定 + 答案 B（混噪生成原文，校准表材料） */
    private record NoiseOutcome(String verdict, String noisyAnswer) {}

    /**
     * 编号化证据渲染（与 {@code RetrievalConfig#formatNumberedContext} 同契约，10.6 / v2.15）：
     * [ref-N] 编号行 + 正文；噪声证据接原证据续编号（K+1 起）。评估侧独立实现，
     * 避免对 kb-ai-core 包私有方法的依赖。
     */
    static String numberedContext(List<RetrievalProbe.ProbeHit> base, List<RetrievalProbe.ProbeHit> noise) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (RetrievalProbe.ProbeHit h : base) {
            sb.append("[ref-").append(++n).append("]\n").append(h.content()).append("\n\n");
        }
        for (RetrievalProbe.ProbeHit h : noise) {
            sb.append("[ref-").append(++n).append("]\n").append(h.content()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * Noise Robustness 抽样映射（簇② 5.8）：正向用例（非负向非注入）按数据集顺序
     * 取前 noiseSampleSize 条，噪声问句 = 数据集内下一条用例问题（循环取）——
     * 确定性：复跑抽到同批用例、同批噪声源，读数可纵向对照。
     * 开关关 / 检索-only / 样本不足时返回空映射。
     */
    Map<String, String> noiseQueryMap(List<GoldenQAPair> dataset) {
        Map<String, String> map = new LinkedHashMap<>();
        int n = props.getMetrics().getNoiseSampleSize();
        if (n <= 0 || props.isRetrievalOnly() || !props.getMetrics().isPhase5Enabled() || dataset.size() < 2) {
            return map;
        }
        List<GoldenQAPair> positive = dataset.stream()
            .filter(p -> !p.isNegative() && !p.isInjection()).toList();
        for (int i = 0; i < Math.min(n, positive.size()); i++) {
            GoldenQAPair target = positive.get(i);
            int idx = dataset.indexOf(target);
            GoldenQAPair noiseSource = dataset.get((idx + 1) % dataset.size());
            map.put(target.id(), noiseSource.question());
        }
        return map;
    }

    /**
     * 注入攻击用例判定（簇⑤ B2 S6）：攻击载荷经 eval 专属护栏链
     * （{@code evalGuardrailChatClient}，仅 InputSanitizeAdvisor）——
     * 捕获 PROMPT_INJECTION → BLOCKED；正常返回 → NOT_BLOCKED（L1 未拦截，
     * 答案丢弃不消费后续指标）。其他 BusinessException / 意外异常按用例失败
     * 上抛（不入拦截率分母，runFullEval 记录后跳过）。
     *
     * <p><b>L1+L2 联合读数（安全簇⑤ E2，用户定案 2026-08-18）</b>：
     * {@code eval.guardrail.l2-enabled=true} 时每例另过联合链
     * （{@code evalGuardrailL2ChatClient}，InputSanitize + SemanticInjection 双
     * advisor，力判键直通——L1 未拦样本逐条进 L2 判定），产出双读数；门禁治
     * L2 判别力（L2 防域子集 JAILBREAK+MULTILINGUAL 联合拦截率）。L1 已拦样本
     * 联合判定恒 BLOCKED（L1 ⊂ 联合链），免重复 L2 LLM 调用。
     */
    private EvalResult evaluateInjection(GoldenQAPair pair) {
        String l1Verdict = callGuardrailChain(guardrailChatClient, pair.question(), false);
        String l2Verdict = null;
        if (props.getGuardrail().isL2Enabled()) {
            l2Verdict = EvalResult.INJECTION_BLOCKED.equals(l1Verdict)
                ? EvalResult.INJECTION_BLOCKED
                : callGuardrailChain(guardrailL2ChatClient, pair.question(), true);
        }
        return injectionResult(pair, l1Verdict, l2Verdict);
    }

    /** 护栏链判定：捕获 PROMPT_INJECTION → BLOCKED；正常返回 → NOT_BLOCKED；其他异常按用例失败上抛 */
    private String callGuardrailChain(ChatClient chain, String question, boolean forceJudge) {
        try {
            ChatClient.ChatClientRequestSpec spec = chain.prompt().user(question);
            if (forceJudge) {
                // 力判直通键（SemanticInjectionAdvisor.FORCE_JUDGE_KEY）：仅 eval 联合链
                // 携带——无视触发启发式逐条进 L2 判定；生产链与 L1 读数链不携带。
                // param 落 advisors spec（实证：与 RagChatService CONVERSATION_ID 同形态）
                spec = spec.advisors(a -> a.param(SemanticInjectionAdvisor.FORCE_JUDGE_KEY, true));
            }
            spec.call().content();
            return EvalResult.INJECTION_NOT_BLOCKED;
        } catch (Exception e) {
            BusinessException be = findBusinessException(e);
            if (be != null && "PROMPT_INJECTION".equals(be.getErrorCode())) {
                return EvalResult.INJECTION_BLOCKED;
            }
            throw e;
        }
    }

    private static EvalResult injectionResult(GoldenQAPair pair, String l1Verdict, String l2Verdict) {
        return new EvalResult(pair, List.of(), null, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, null, null, null, null, null, l1Verdict, l2Verdict,
            null, null, null, null, null, null);
    }

    /** 异常链中提取 BusinessException（ChatClient 调用层可能包裹原因链） */
    private static BusinessException findBusinessException(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof BusinessException be) {
                return be;
            }
            if (cur.getCause() == cur) {
                break;
            }
        }
        return null;
    }

    private static RetrievalProbe selectProbe(List<RetrievalProbe> probes, String mode) {
        if ("vector".equalsIgnoreCase(mode)) {
            return probes.stream().filter(p -> "vector-single".equals(p.name())).findFirst()
                .orElseThrow(() -> new IllegalStateException("eval.probe=vector 但无单路探针"));
        }
        if ("hybrid".equalsIgnoreCase(mode)) {
            return probes.stream().filter(p -> "hybrid".equals(p.name())).findFirst()
                .orElseThrow(() -> new IllegalStateException("eval.probe=hybrid 但无混合探针"));
        }
        if ("chain".equalsIgnoreCase(mode)) {
            return probes.stream().filter(p -> "chain".equals(p.name())).findFirst()
                .orElseThrow(() -> new IllegalStateException("eval.probe=chain 但无全链路探针"));
        }
        // auto：order 最小者胜出
        return probes.stream()
            .min(Comparator.comparingInt(RetrievalProbe::getOrder))
            .orElseThrow(() -> new IllegalStateException("无可用 RetrievalProbe"));
    }

    private JudgePrompts.JudgeScore judge(String prompt) {
        return judgeChatClient.prompt().user(prompt)
            .call().entity(JudgePrompts.JudgeScore.class);
    }

    private Double scoreOf(JudgePrompts.JudgeScore js) {
        return js == null || js.score() == null ? null : js.score().doubleValue();
    }

    private EvalReport aggregate(String probeName, List<EvalResult> results) {
        List<EvalResult> withRetrieval = results.stream()
            .filter(r -> !Double.isNaN(r.recall())).toList();
        List<EvalResult> withDocRetrieval = results.stream()
            .filter(r -> !Double.isNaN(r.docRecall())).toList();
        List<EvalResult> generation = results.stream()
            .filter(r -> !r.isNegative() && r.faithfulness() != null).toList();
        List<EvalResult> negative = results.stream()
            .filter(r -> r.isNegative() && r.rejectionVerdict() != null).toList();

        long rejected = negative.stream()
            .filter(r -> "REJECTED".equalsIgnoreCase(r.rejectionVerdict())).count();

        // 注入拦截统计（簇⑤ B2 S6）：总体 / 门禁子集（DIRECT+ENCODING_BYPASS）/ 按攻击类型
        List<EvalResult> injection = results.stream()
            .filter(r -> r.pair().isInjection() && r.injectionVerdict() != null).toList();
        List<EvalResult> injectionGate = injection.stream()
            .filter(r -> r.pair().isInjectionGateSubset()).toList();
        Map<AttackType, Double> blockRateByAttackType = new LinkedHashMap<>();
        for (AttackType type : AttackType.values()) {
            List<EvalResult> ofType = injection.stream()
                .filter(r -> r.pair().attackType() == type).toList();
            if (!ofType.isEmpty()) {
                blockRateByAttackType.put(type, blockRate(ofType));
            }
        }

        return new EvalReport(
            probeName,
            results.size(),
            withRetrieval.size(),
            generation.size(),
            negative.size(),
            avg(withRetrieval, EvalResult::recall),
            avg(withRetrieval, EvalResult::mrr),
            avg(withRetrieval, EvalResult::contextPrecision),
            withDocRetrieval.size(),
            avg(withDocRetrieval, EvalResult::docRecall),
            avg(withDocRetrieval, EvalResult::docMrr),
            avg(withDocRetrieval, EvalResult::docContextPrecision),
            avg(generation, r -> r.faithfulness()),
            avg(generation, r -> r.responseRelevancy()),
            negative.isEmpty() ? Double.NaN : (double) rejected / negative.size(),
            injection.size(),
            blockRate(injection),
            injectionGate.size(),
            blockRate(injectionGate),
            blockRateByAttackType,
            results,
            aggregatePhase5(results));
    }

    /**
     * Phase 5 扩展指标聚合（簇② 5.8）：
     * <ul>
     *   <li>AC：expectedAnswer 标注且 Judge 产出（当前语料零标注 → 0 样本）；</li>
     *   <li>CA：生成成功的正向用例为分母（含 NO_CITATION——grounding 契约要求引用，
     *       未发出判负），SUPPORTED 为通过；</li>
     *   <li>HR：声明级无依据占比均值；</li>
     *   <li>NRob：噪声抽样且对照有效（CONSISTENT/DRIFTED 判定产出）为分母。</li>
     * </ul>
     */
    static EvalReport.Phase5Metrics aggregatePhase5(List<EvalResult> results) {
        List<EvalResult> ac = results.stream()
            .filter(r -> r.answerCorrectness() != null).toList();
        List<EvalResult> ca = results.stream()
            .filter(r -> r.citationVerdict() != null).toList();
        List<EvalResult> hr = results.stream()
            .filter(r -> r.hallucinationRate() != null).toList();
        List<EvalResult> noise = results.stream()
            .filter(r -> r.noiseVerdict() != null).toList();
        if (ac.isEmpty() && ca.isEmpty() && hr.isEmpty() && noise.isEmpty()) {
            return EvalReport.Phase5Metrics.EMPTY;
        }
        long caSupported = ca.stream()
            .filter(r -> CitationMetrics.VERDICT_SUPPORTED.equals(r.citationVerdict())).count();
        long noiseConsistent = noise.stream()
            .filter(r -> "CONSISTENT".equals(r.noiseVerdict())).count();
        return new EvalReport.Phase5Metrics(
            ac.size(), avg(ac, EvalResult::answerCorrectness),
            ca.size(), ca.isEmpty() ? Double.NaN : (double) caSupported / ca.size(),
            hr.size(), avg(hr, EvalResult::hallucinationRate),
            noise.size(), noise.isEmpty() ? Double.NaN : (double) noiseConsistent / noise.size());
    }

    /** 拦截率 = BLOCKED / 样本数；空样本返回 NaN（门禁与报告按 NaN 跳过） */
    private static double blockRate(List<EvalResult> injectionCases) {
        if (injectionCases.isEmpty()) {
            return Double.NaN;
        }
        long blocked = injectionCases.stream().filter(EvalResult::isInjectionBlocked).count();
        return (double) blocked / injectionCases.size();
    }

    private static double avg(List<EvalResult> list, java.util.function.ToDoubleFunction<EvalResult> f) {
        if (list.isEmpty()) return Double.NaN;
        return list.stream().mapToDouble(f).average().orElse(Double.NaN);
    }

    /** 按分类抽样（sampleSize>0 时每类最多 N 条，CI 快跑用） */
    private List<GoldenQAPair> sample(List<GoldenQAPair> all) {
        int n = props.getSampleSize();
        if (n <= 0) return all;
        return all.stream()
            .collect(Collectors.groupingBy(GoldenQAPair::category))
            .values().stream()
            .flatMap(groups -> groups.stream().limit(n))
            .toList();
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }
}
