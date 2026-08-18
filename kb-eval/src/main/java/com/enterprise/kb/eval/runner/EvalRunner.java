package com.enterprise.kb.eval.runner;

import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.ai.advisor.SemanticInjectionAdvisor;
import com.enterprise.kb.eval.config.EvalProperties;
import com.enterprise.kb.eval.dataset.AttackType;
import com.enterprise.kb.eval.dataset.GoldenQAPair;
import com.enterprise.kb.eval.dataset.GoldenDatasetLoader;
import com.enterprise.kb.eval.dataset.QACategory;
import com.enterprise.kb.eval.metric.JudgePrompts;
import com.enterprise.kb.eval.metric.RetrievalMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

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
    private final ChatClient judgeChatClient;   // Judge（跨厂商，16.3）
    private final ChatClient guardrailChatClient; // INJECTION 专属护栏链（簇⑤ B2 S6）
    private final ChatClient guardrailL2ChatClient; // INJECTION L1+L2 联合护栏链（安全簇⑤ E2）
    private final IndirectInjectionRunner indirectInjectionRunner; // 间接注入评估（簇④ D3）
    private final EvalProperties props;
    private final ApplicationArguments args;

    public EvalRunner(GoldenDatasetLoader datasetLoader,
                      List<RetrievalProbe> probes,
                      @Qualifier("chatClient") ChatClient chatClient,
                      @Qualifier("judgeChatClient") ChatClient judgeChatClient,
                      @Qualifier("evalGuardrailChatClient") ChatClient guardrailChatClient,
                      @Qualifier("evalGuardrailL2ChatClient") ChatClient guardrailL2ChatClient,
                      IndirectInjectionRunner indirectInjectionRunner,
                      EvalProperties props,
                      ApplicationArguments args) {
        this.datasetLoader = datasetLoader;
        // 探针选择：auto = order 最小者胜出（混合探针 order=0 自动替代单路基线 order=100）；
        //          vector/hybrid = 显式指定，用于 A/B 基线对比
        this.retrievalProbe = selectProbe(probes, props.getProbe());
        this.chatClient = chatClient;
        this.judgeChatClient = judgeChatClient;
        this.guardrailChatClient = guardrailChatClient;
        this.guardrailL2ChatClient = guardrailL2ChatClient;
        this.indirectInjectionRunner = indirectInjectionRunner;
        this.props = props;
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
        if (!ci && (args.containsOption("eval.annotate-query") || args.containsOption("eval.annotate-all"))) {
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
     * 文件名带标签——簇④ E1 校准复跑与 A/B 快照各留独立文件，避免互覆）
     */
    private void publishReport(EvalReport report) {
        String summary = report.summary();
        if (props.isRetrievalOnly()) {
            summary = "【检索-only 模式】生成侧与 Judge 已跳过，仅检索侧指标有效"
                + System.lineSeparator() + summary;
        }
        System.out.println(summary);
        log.info("\n{}", summary);
        try {
            String label = props.getRunLabel() == null ? "" : props.getRunLabel().trim();
            String fileName = label.isEmpty() ? "eval-report.txt" : "eval-report-" + label + ".txt";
            java.nio.file.Path out = java.nio.file.Path.of("target", fileName);
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, summary + System.lineSeparator());
            log.info("评估报告已写入: {}", out.toAbsolutePath());
        } catch (Exception e) {
            log.warn("评估报告落盘失败（不影响门禁）: {}", e.getMessage());
        }
    }

    /**
     * 人工-Judge 一致率抽样表（簇④ E1，judge-agreement-sample > 0 时启用）：
     * 全量评估后按分类分层抽 N 条正向用例，落盘 target/judge-agreement-sheet.md
     * 供人工盲打分，度量 Judge 可信度（一致口径 |人工−Judge|≤1，目标 ≥85%）。
     * 检索-only / ci 门禁模式无意义，跳过。
     */
    private void writeJudgeAgreementSheetIfNeeded(EvalReport report) {
        int n = props.getJudgeAgreementSample();
        if (n <= 0 || props.isRetrievalOnly()) {
            return;
        }
        List<EvalResult> generation = report.results().stream()
            .filter(r -> !r.isNegative() && r.faithfulness() != null).toList();
        if (generation.isEmpty()) {
            log.warn("一致率抽样跳过：无生成侧评估结果");
            return;
        }
        List<EvalResult> sampled = stratifiedSample(generation, n, 42L);
        try {
            java.nio.file.Path out = java.nio.file.Path.of("target", "judge-agreement-sheet.md");
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, renderAgreementSheet(sampled));
            log.info("人工-Judge 一致率抽样表（{} 条）已写入: {}", sampled.size(), out.toAbsolutePath());
        } catch (Exception e) {
            log.warn("一致率抽样表落盘失败（不影响评估）: {}", e.getMessage());
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

    private String renderAgreementSheet(List<EvalResult> sampled) {
        EvalProperties.Judge j = props.getJudge();
        StringBuilder sb = new StringBuilder();
        sb.append("# 人工-Judge 一致率打分表（簇④ E1）").append(System.lineSeparator());
        sb.append(System.lineSeparator());
        sb.append(String.format("- Judge: %s（temperature=%.1f, enable_thinking=%s）%n",
            j.getModel(), j.getTemperature(), j.isEnableThinking()));
        sb.append(String.format("- 运行标签: %s%n",
            props.getRunLabel() == null || props.getRunLabel().isBlank() ? "（无）" : props.getRunLabel()));
        sb.append("- 打分口径：仅评【回答】对【参考资料】的忠实度（同 Judge 的 Faithfulness 维度），1-5 整数").append(System.lineSeparator());
        sb.append("- 一致判定：|人工分 − Judge 分| ≤ 1 记一致；目标一致率 ≥ 85%").append(System.lineSeparator());
        sb.append(System.lineSeparator());
        sb.append("| # | 用例 ID | 分类 | Judge 分 | 人工分（填写） |").append(System.lineSeparator());
        sb.append("|---|---|---|---|---|").append(System.lineSeparator());
        for (int i = 0; i < sampled.size(); i++) {
            EvalResult r = sampled.get(i);
            sb.append(String.format("| %d | %s | %s | %.0f | |%n",
                i + 1, r.pair().id(), r.pair().category(), r.faithfulness()));
        }
        sb.append(System.lineSeparator()).append("---").append(System.lineSeparator());
        for (int i = 0; i < sampled.size(); i++) {
            EvalResult r = sampled.get(i);
            String context = r.hits() == null ? "" : r.hits().stream()
                .map(h -> "[%s] %s".formatted(h.chunkId(), truncate(h.content(), 800)))
                .collect(Collectors.joining("\n\n"));
            sb.append(String.format("%n## %d. %s（%s）%n%n", i + 1, r.pair().id(), r.pair().category()));
            sb.append("**问题**：").append(r.pair().question()).append(System.lineSeparator());
            sb.append(System.lineSeparator()).append("**参考资料**（Judge 所见）：").append(System.lineSeparator());
            sb.append(System.lineSeparator()).append(context).append(System.lineSeparator());
            sb.append(System.lineSeparator()).append("**模型回答**：").append(System.lineSeparator());
            sb.append(System.lineSeparator()).append(r.answer() == null ? "（生成失败）" : r.answer()).append(System.lineSeparator());
            sb.append(System.lineSeparator()).append(String.format("**Judge 评分**：%.0f%n%n", r.faithfulness()));
            sb.append("**Judge 理由**：").append(r.judgeReason() == null ? "（无）" : r.judgeReason()).append(System.lineSeparator());
            sb.append(System.lineSeparator()).append("**人工分**：____（1-5 整数）").append(System.lineSeparator());
        }
        return sb.toString();
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
                        return evaluateOne(pair);
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

    private EvalResult evaluateOne(GoldenQAPair pair) {
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
                docRecall, docMrr, docPrecision, null, null, null, null, null, null, null);
        }

        // 3. 被测链路生成
        String answer = chatClient.prompt().user(pair.question()).call().content();

        // 4. Judge 评分
        if (pair.isNegative()) {
            JudgePrompts.JudgeScore js = judge(String.format(
                JudgePrompts.NEGATIVE_REJECTION, pair.question(), answer));
            return new EvalResult(pair, hits, answer, recall, mrr, precision,
                docRecall, docMrr, docPrecision, null, null, js.verdict(), scoreOf(js), js.reason(), null, null);
        }
        String context = hits.stream()
            .map(h -> "[%s] %s".formatted(h.chunkId(), truncate(h.content(), 800)))
            .collect(Collectors.joining("\n\n"));
        JudgePrompts.JudgeScore faithfulness = judge(String.format(
            JudgePrompts.FAITHFULNESS, pair.question(), context, answer));
        JudgePrompts.JudgeScore relevancy = judge(String.format(
            JudgePrompts.RESPONSE_RELEVANCY, pair.question(), answer));
        return new EvalResult(pair, hits, answer, recall, mrr, precision,
            docRecall, docMrr, docPrecision,
            scoreOf(faithfulness), scoreOf(relevancy), null, null, faithfulness.reason(), null, null);
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
            Double.NaN, Double.NaN, Double.NaN, null, null, null, null, null, l1Verdict, l2Verdict);
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
            results);
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
