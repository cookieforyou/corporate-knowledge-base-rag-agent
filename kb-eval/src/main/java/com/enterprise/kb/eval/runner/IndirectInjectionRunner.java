package com.enterprise.kb.eval.runner;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.eval.config.EvalProperties;
import com.enterprise.kb.eval.dataset.IndirectDatasetLoader;
import com.enterprise.kb.eval.dataset.IndirectQAPair;
import com.enterprise.kb.eval.metric.JudgePrompts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 间接注入评估运行器（安全簇④ D3，设计 §12.8 / 12.6 提案落地）
 *
 * <p>度量「S2 统一声明 + D1 逐条警示」联合防御面对毒化语料的抑制率：
 * <ol>
 *   <li>触发问句（正常业务问句）走 eval {@code chatClient} 生产等价链，
 *       毒化文档经检索注入 grounding（与 ChainRetrievalProbe 同参数链形态，
 *       {@code eval.chain-probe.tenant-id} 租户必填——检索器 fail-closed）；</li>
 *   <li><b>打标自洽校验</b>：final trace 中 file_name 匹配用例的证据应携带
 *       S4 入库打标（{@code injection_hit}，kb-etl SanitizingTransformer 同源词表）
 *       与 D1 运行时标记（{@code indirect_injection_hit}，warn 策略）——假阴性
 *       即防线失效红灯，逐例上报；</li>
 *   <li>Judge 判定回答是否执行资料内植入指令（EXECUTED/RESISTED，
 *       {@link JudgePrompts#INDIRECT_INJECTION}，判据为期望忠实行为结构描述）；</li>
 *   <li><b>抑制率 = RESISTED / 总数</b>，首跑基线入档
 *       {@code target/indirect-eval{-label}.txt/.json}（EVAL_RUN_LABEL 快照纪律，
 *       warn/exclude 策略与开关组合各留独立 label 即防线增益对照系）。</li>
 * </ol>
 *
 * <p><b>执行形态定案（簇④分解 D3a/D3b）</b>：毒化文档的上传走生产管道
 * （用户侧经前端/API 入库，100% 真实 ETL 保真度）；本运行器只做触发 + 自洽 +
 * Judge + 度量。语料正文经 {@link IndirectDatasetLoader} 解码仅用于指纹自洽
 * （检索命中 chunk 文本与归档语料同源性校验），程序化消费不经会话（§7 纪律）。
 *
 * <p>敏感词交付纪律（簇④分解条 5-7）：报告与日志只输出用例 ID、布尔、
 * verdict 枚举与计数，不回显毒化正文与回答原文。
 */
@Slf4j
@Component
public class IndirectInjectionRunner {

    /** verdict 枚举：抵抗成功（未执行植入指令） */
    public static final String VERDICT_RESISTED = "RESISTED";
    /** verdict 枚举：执行了植入指令 */
    public static final String VERDICT_EXECUTED = "EXECUTED";

    /** S4 入库打标元数据键（契约源 kb-etl SanitizingTransformer） */
    static final String INJECTION_HIT_KEY = "injection_hit";
    /** D1 运行时扫描标记元数据键（契约源 kb-ai-core IndirectInjectionScanPostProcessor） */
    static final String INDIRECT_HIT_KEY = "indirect_injection_hit";

    private final IndirectDatasetLoader datasetLoader;
    private final ChatClient chatClient;
    private final ChatClient judgeChatClient;
    private final EvalProperties props;
    private final JsonMapper jsonMapper;
    private final String tenantId;

    public IndirectInjectionRunner(
            IndirectDatasetLoader datasetLoader,
            @Qualifier("chatClient") ChatClient chatClient,
            @Qualifier("judgeChatClient") ChatClient judgeChatClient,
            EvalProperties props,
            JsonMapper jsonMapper,
            @Value("${eval.chain-probe.tenant-id:}") String tenantId) {
        this.datasetLoader = datasetLoader;
        this.chatClient = chatClient;
        this.judgeChatClient = judgeChatClient;
        this.props = props;
        this.jsonMapper = jsonMapper;
        this.tenantId = tenantId;
    }

    /** 单用例评估结果（ID/布尔/枚举/计数形态——无内容字面） */
    public record IndirectCaseResult(
            String id,
            String verdict,
            boolean retrievedExpectedFile,
            boolean taggedIngestion,
            boolean taggedRuntime,
            boolean corpusConsistent) {}

    /** 汇总（抑制率 + 逐例结果） */
    public record IndirectEvalSummary(
            int totalCases,
            int resisted,
            int executed,
            double suppressionRate,
            int ingestionTagMisses,
            int runtimeTagMisses,
            List<IndirectCaseResult> cases) {}

    /**
     * 按需执行：总开关关或语料空返回 null（缺省合法形态，日志提示前置步骤）。
     * 租户未配置快失败（对齐 ChainRetrievalProbe fail-closed 适配纪律）。
     */
    public IndirectEvalSummary runIfNeeded() {
        if (!props.getIndirect().isEnabled()) {
            return null;
        }
        List<IndirectQAPair> corpus = datasetLoader.loadAll();
        if (corpus.isEmpty()) {
            log.warn("间接注入评估已开启但语料为空——经 tools/guardrail/import_poison_corpus.py "
                + "带外注入语料并经生产管道上传毒化文档后复跑");
            return null;
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException(
                "间接注入评估需设置 eval.chain-probe.tenant-id（检索器对有 ctx 无租户 fail-closed 返回空）");
        }
        log.info("间接注入评估开始：{} 条毒化语料用例", corpus.size());

        List<IndirectCaseResult> cases = new ArrayList<>();
        for (IndirectQAPair pair : corpus) {
            try {
                IndirectCaseResult result = evaluateOne(pair);
                cases.add(result);
                log.info("[间接注入] {} ✓ verdict={}（入库打标 {} / 运行时标记 {} / 语料自洽 {}）",
                    pair.id(), result.verdict(),
                    result.taggedIngestion() ? "✓" : "✗",
                    result.taggedRuntime() ? "✓" : "✗",
                    result.corpusConsistent() ? "✓" : "✗");
            } catch (Exception e) {
                log.error("[间接注入] {} 评估失败: {}", pair.id(), e.getMessage());
            }
        }
        IndirectEvalSummary summary = summarize(cases);
        publishReport(summary);
        return summary;
    }

    private IndirectCaseResult evaluateOne(IndirectQAPair pair) {
        // 1. 触发问句走生产等价链：毒化文档经检索注入 grounding，捕获回答与 final trace
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId(tenantId);
        String answer = chatClient.prompt().user(pair.question())
            .advisors(spec -> spec.param(RetrievalContext.CONTEXT_KEY, ctx))
            .call()
            .content();

        List<Document> finals = ctx.getTraceSummary().stream()
            .filter(e -> "final".equals(e.source()))
            .findFirst()
            .map(RetrievalContext.TraceEntry::documents)
            .orElse(List.of());

        // 2. 打标自洽校验：期望文件命中证据应双标记齐备（S4 入库 + D1 运行时）
        List<Document> matched = finals.stream()
            .filter(d -> pair.fileName() != null
                && pair.fileName().equals(String.valueOf(d.getMetadata().get("file_name"))))
            .toList();
        boolean retrievedExpectedFile = !matched.isEmpty();
        boolean taggedIngestion = matched.stream()
            .anyMatch(d -> Boolean.TRUE.equals(d.getMetadata().get(INJECTION_HIT_KEY)));
        boolean taggedRuntime = matched.stream()
            .anyMatch(d -> Boolean.TRUE.equals(d.getMetadata().get(INDIRECT_HIT_KEY)));

        // 3. 语料同源自洽：命中证据正文应源出归档毒化语料（解码文本包含性校验，
        //    程序化比对——内容不经会话；chunk 为语料切片，语料全文包含 chunk 文本）
        boolean corpusConsistent = retrievedExpectedFile && matched.stream()
            .anyMatch(d -> d.getText() != null && !d.getText().isBlank()
                && pair.document() != null && pair.document().contains(d.getText().trim()));

        // 4. Judge 判定：EXECUTED / RESISTED（判据为期望忠实行为结构描述）
        String verdict = judge(pair, answer);
        return new IndirectCaseResult(pair.id(), verdict,
            retrievedExpectedFile, taggedIngestion, taggedRuntime, corpusConsistent);
    }

    private String judge(IndirectQAPair pair, String answer) {
        String prompt = JudgePrompts.INDIRECT_INJECTION.formatted(
            pair.question(), pair.judgeCriteria(), answer == null ? "" : answer);
        JudgePrompts.JudgeScore js = judgeChatClient.prompt().user(prompt)
            .call().entity(JudgePrompts.JudgeScore.class);
        if (js == null || js.verdict() == null) {
            throw new IllegalStateException("间接注入 Judge 未返回 verdict");
        }
        String verdict = js.verdict().trim().toUpperCase();
        if (!VERDICT_RESISTED.equals(verdict) && !VERDICT_EXECUTED.equals(verdict)) {
            throw new IllegalStateException("间接注入 Judge verdict 非法: " + verdict);
        }
        return verdict;
    }

    static IndirectEvalSummary summarize(List<IndirectCaseResult> cases) {
        int resisted = (int) cases.stream()
            .filter(c -> VERDICT_RESISTED.equals(c.verdict())).count();
        int executed = (int) cases.stream()
            .filter(c -> VERDICT_EXECUTED.equals(c.verdict())).count();
        int ingestionMisses = (int) cases.stream()
            .filter(c -> c.retrievedExpectedFile() && !c.taggedIngestion()).count();
        int runtimeMisses = (int) cases.stream()
            .filter(c -> c.retrievedExpectedFile() && !c.taggedRuntime()).count();
        double suppressionRate = cases.isEmpty() ? Double.NaN : (double) resisted / cases.size();
        return new IndirectEvalSummary(cases.size(), resisted, executed, suppressionRate,
            ingestionMisses, runtimeMisses, cases);
    }

    /** 报告双通道（对齐 publishReport 形态）：stdout 直出 + target 落盘（txt + json 入档） */
    private void publishReport(IndirectEvalSummary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("── 安全性（间接注入抑制，安全簇④ D3）──").append(System.lineSeparator());
        sb.append(String.format("毒化语料 %d 例：RESISTED %d / EXECUTED %d → 抑制率 %.3f%n",
            summary.totalCases(), summary.resisted(), summary.executed(), summary.suppressionRate()));
        if (summary.ingestionTagMisses() > 0 || summary.runtimeTagMisses() > 0) {
            sb.append(String.format("⚠ 打标自洽缺口：入库打标缺失 %d 例 / 运行时标记缺失 %d 例（防线失效红灯，逐例见下）%n",
                summary.ingestionTagMisses(), summary.runtimeTagMisses()));
        }
        sb.append(String.format("%-12s %-10s %-6s %-6s %-6s %-6s%n",
            "用例ID", "verdict", "文件命中", "入库标", "运行时标", "语料自洽"));
        for (IndirectCaseResult c : summary.cases()) {
            sb.append(String.format("%-12s %-10s %-6s %-6s %-6s %-6s%n",
                c.id(), c.verdict(),
                c.retrievedExpectedFile() ? "✓" : "✗",
                c.taggedIngestion() ? "✓" : "✗",
                c.taggedRuntime() ? "✓" : "✗",
                c.corpusConsistent() ? "✓" : "✗"));
        }
        String report = sb.toString();
        log.info(System.lineSeparator() + report);
        System.out.println(report);

        String labelSuffix = props.getRunLabel() == null || props.getRunLabel().isBlank()
            ? "" : "-" + props.getRunLabel();
        try {
            Path dir = Path.of("target");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("indirect-eval" + labelSuffix + ".txt"), report);
            Files.writeString(dir.resolve("indirect-eval" + labelSuffix + ".json"),
                jsonMapper.writeValueAsString(summary));
            log.info("间接注入评估报告已入档: target/indirect-eval{}（txt + json）", labelSuffix);
        } catch (IOException e) {
            log.warn("间接注入评估报告落盘失败（不影响读数）: {}", e.getMessage());
        }
    }
}
