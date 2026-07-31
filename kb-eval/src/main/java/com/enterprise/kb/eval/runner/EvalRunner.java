package com.enterprise.kb.eval.runner;

import com.enterprise.kb.eval.config.EvalProperties;
import com.enterprise.kb.eval.dataset.GoldenQAPair;
import com.enterprise.kb.eval.dataset.GoldenDatasetLoader;
import com.enterprise.kb.eval.dataset.QACategory;
import com.enterprise.kb.eval.metric.JudgePrompts;
import com.enterprise.kb.eval.metric.RetrievalMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
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
    private final EvalProperties props;

    public EvalRunner(GoldenDatasetLoader datasetLoader,
                      List<RetrievalProbe> probes,
                      @Qualifier("chatClient") ChatClient chatClient,
                      @Qualifier("judgeChatClient") ChatClient judgeChatClient,
                      EvalProperties props) {
        this.datasetLoader = datasetLoader;
        // 探针选择：auto = order 最小者胜出（混合探针 order=0 自动替代单路基线 order=100）；
        //          vector/hybrid = 显式指定，用于 A/B 基线对比
        this.retrievalProbe = selectProbe(probes, props.getProbe());
        this.chatClient = chatClient;
        this.judgeChatClient = judgeChatClient;
        this.props = props;
    }

    /** CI 门禁入口：ci profile 下启动即跑，低于阈值抛 EvalFailedException（进程非零退出） */
    @EventListener(ApplicationReadyEvent.class)
    public void runIfCiMode() {
        if (!props.getCi().isEnabled()) {
            log.info("eval.ci.enabled=false，跳过门禁评估（手动运行见 README）");
            return;
        }
        // 前置快失败：Judge 密钥缺失时所有生成侧评分必然失败，不允许静默「通过」
        if (props.getJudge().getApiKey() == null || props.getJudge().getApiKey().isBlank()) {
            throw new EvalFailedException("DASHSCOPE_API_KEY 未配置，Judge 不可用——门禁拒绝运行");
        }
        EvalReport report = runFullEval();
        publishReport(report);
        report.assertThresholds(props);
        log.info("✅ 评估门禁通过");
    }

    /**
     * 报告双通道发布：① stdout 直出（不依赖日志配置，CI 日志必可见）；
     * ② 落盘 target/eval-report.txt（本地可复查的历史产物）
     */
    private void publishReport(EvalReport report) {
        String summary = report.summary();
        System.out.println(summary);
        log.info("\n{}", summary);
        try {
            java.nio.file.Path out = java.nio.file.Path.of("target", "eval-report.txt");
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, summary + System.lineSeparator());
            log.info("评估报告已写入: {}", out.toAbsolutePath());
        } catch (Exception e) {
            log.warn("评估报告落盘失败（不影响门禁）: {}", e.getMessage());
        }
    }

    public EvalReport runFullEval() {
        List<GoldenQAPair> dataset = sample(datasetLoader.loadAll());
        if (dataset.isEmpty()) {
            throw new EvalFailedException("Golden Dataset 为空（classpath:golden/*.json 无可用用例）");
        }
        log.info("开始评估：{} 条用例，检索探针 = {}", dataset.size(), retrievalProbe.name());

        List<EvalResult> results = new ArrayList<>();
        for (int i = 0; i < dataset.size(); i++) {
            GoldenQAPair pair = dataset.get(i);
            try {
                results.add(evaluateOne(pair));
                log.info("[{}/{}] {} ✓", i + 1, dataset.size(), pair.id());
            } catch (Exception e) {
                log.error("[{}/{}] {} 评估失败: {}", i + 1, dataset.size(), pair.id(), e.getMessage());
            }
        }
        // 全部用例失败（基础设施不可达/密钥错误等）不得静默「通过」
        if (results.isEmpty()) {
            throw new EvalFailedException(
                "无有效评估结果（全部 " + dataset.size() + " 条用例失败）——请检查 ECS 基础设施连通性与 API Keys");
        }
        return aggregate(retrievalProbe.name(), results);
    }

    private EvalResult evaluateOne(GoldenQAPair pair) {
        // 1. 检索取数
        List<RetrievalProbe.ProbeHit> hits = retrievalProbe.probe(pair.question(), props.getTopK());
        List<String> hitIds = hits.stream().map(RetrievalProbe.ProbeHit::chunkId).toList();

        // 2. 检索指标（无期望标注 → NaN，聚合时跳过）
        double recall = RetrievalMetrics.recallAtK(hitIds, pair.expectedChunkIds());
        double mrr = RetrievalMetrics.reciprocalRank(hitIds, pair.expectedChunkIds());
        double precision = RetrievalMetrics.contextPrecision(hitIds, pair.expectedChunkIds());

        // 3. 被测链路生成
        String answer = chatClient.prompt().user(pair.question()).call().content();

        // 4. Judge 评分
        if (pair.isNegative()) {
            JudgePrompts.JudgeScore js = judge(String.format(
                JudgePrompts.NEGATIVE_REJECTION, pair.question(), answer));
            return new EvalResult(pair, hits, answer, recall, mrr, precision,
                null, null, js.verdict(), scoreOf(js), js.reason());
        }
        String context = hits.stream()
            .map(h -> "[%s] %s".formatted(h.chunkId(), truncate(h.content(), 800)))
            .collect(Collectors.joining("\n\n"));
        JudgePrompts.JudgeScore faithfulness = judge(String.format(
            JudgePrompts.FAITHFULNESS, pair.question(), context, answer));
        JudgePrompts.JudgeScore relevancy = judge(String.format(
            JudgePrompts.RESPONSE_RELEVANCY, pair.question(), answer));
        return new EvalResult(pair, hits, answer, recall, mrr, precision,
            scoreOf(faithfulness), scoreOf(relevancy), null, null, faithfulness.reason());
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
        List<EvalResult> generation = results.stream()
            .filter(r -> !r.isNegative() && r.faithfulness() != null).toList();
        List<EvalResult> negative = results.stream()
            .filter(r -> r.isNegative() && r.rejectionVerdict() != null).toList();

        long rejected = negative.stream()
            .filter(r -> "REJECTED".equalsIgnoreCase(r.rejectionVerdict())).count();

        return new EvalReport(
            probeName,
            results.size(),
            withRetrieval.size(),
            generation.size(),
            negative.size(),
            avg(withRetrieval, EvalResult::recall),
            avg(withRetrieval, EvalResult::mrr),
            avg(withRetrieval, EvalResult::contextPrecision),
            avg(generation, r -> r.faithfulness()),
            avg(generation, r -> r.responseRelevancy()),
            negative.isEmpty() ? Double.NaN : (double) rejected / negative.size(),
            results);
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
