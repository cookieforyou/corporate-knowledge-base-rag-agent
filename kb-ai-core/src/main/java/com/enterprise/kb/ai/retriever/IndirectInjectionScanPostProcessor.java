package com.enterprise.kb.ai.retriever;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.commons.guardrail.GuardrailRule;
import com.enterprise.kb.commons.guardrail.GuardrailRulesLoader;
import com.enterprise.kb.commons.security.TextSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 间接注入扫描后处理器（安全簇④ D1，设计 §12.8；缺口 E3 闭环第一环）
 *
 * <p>证据注入 grounding 前对每个召回 chunk 跑注入词表检测视图（复用簇①词表工程
 * 资产，{@link GuardrailRulesLoader} 同源装载，与 InputSanitizeAdvisor /
 * SanitizingTransformer 同词面不漂移）——S2 是 grounding 模板统一声明，本组件是
 * <b>逐条定位强化</b>：命中的证据在模板渲染时追加逐条警示注记
 * （{@code RetrievalConfig#formatNumberedContext} 元数据感知消费
 * {@link #INDIRECT_HIT_KEY} 标记）。
 *
 * <p><b>检测视图口径</b>：归一化（{@link TextSanitizer#normalize}）后 BLOCK+FLAG
 * 全档命中即判中——间接面不拒绝请求（载荷在检索文档而非用户输入），命中只触发
 * 警示/剔除干预，与输入侧 BLOCK 拒绝语义分治。视图仅供检测不回写（同 S1 纪律）。
 *
 * <p><b>策略双档</b>（{@code rag.guardrail.indirect.strategy}）：
 * <ul>
 *   <li>{@code warn}（默认）：命中文档打标保留——grounding 逐条警示注记 +
 *       {@code rag.guardrail.indirect.flagged} 计数；</li>
 *   <li>{@code exclude}：命中文档剔除——不进 grounding 与 rerank，另计
 *       {@code rag.guardrail.indirect.excluded}；全剔则链路自然回落空证据拒答
 *       （EMPTY_CONTEXT_PROMPT 既有机制零改动）。</li>
 * </ul>
 *
 * <p><b>装配位序（定案）</b>：本处理器置于 rerank 后处理器<b>之前</b>
 * （RetrievalAugmentationAdvisor documentPostProcessors 序列首位）——
 * exclude 剔除的 chunk 不参与重排、不进 rerank 写入的 final TRACE 帧，
 * grounding / 审计溯源 / 前端 [ref-N] 三面对齐关系不破；warn 档文档集不变，
 * 对齐天然保持。正则词表扫描开销毫秒级（每请求 topK×2 条），可忽略。
 *
 * <p><b>审计口径（定案）</b>：命中不写 kb_audit_log.guardrail_flags——该列
 * side 枚举为 input/output 双侧语义，间接面以独立指标表达；审计行维持
 * SUCCESS→null 不变量（免 schema 变更）。
 *
 * <p>敏感词交付纪律（§7 / 簇④分解条 5-7）：warn 日志只记命中计数与文档 ID，
 * 不落正文；词表内容编码态存储加载层解码，本组件零字面词面。
 */
@Slf4j
@Component
public class IndirectInjectionScanPostProcessor implements DocumentPostProcessor {

    /**
     * 命中文档元数据标记键（warn 档）：编号格式器据此渲染逐条警示注记。
     * 值恒为 {@link Boolean#TRUE}（元数据禁 null，坑位④）。
     */
    public static final String INDIRECT_HIT_KEY = "indirect_injection_hit";

    /** exclude 策略配置值（warn 为缺省策略，非法值回落 warn 并启动 warn） */
    private static final String STRATEGY_EXCLUDE = "exclude";
    private static final String STRATEGY_WARN = "warn";

    private final List<GuardrailRule> injectionRules;
    private final AiBusinessMetrics metrics;
    private final boolean enabled;
    private final boolean excludeStrategy;

    /**
     * 装配构造器——双构造器形态必须显式钉 {@link Autowired}（Spring 6 多构造器
     * 无注解即回落无参构造器致启动失败；先例 ContextualEnrichmentTransformer 同形态）。
     */
    @Autowired
    public IndirectInjectionScanPostProcessor(
            @Value("${rag.guardrail.rules.injection-location:}") String rulesLocation,
            @Value("${rag.guardrail.input.injection-keywords:}") String keywordsCsv,
            AiBusinessMetrics metrics,
            @Value("${rag.guardrail.indirect.scan.enabled:true}") boolean enabled,
            @Value("${rag.guardrail.indirect.strategy:warn}") String strategy) {
        this(GuardrailRulesLoader.loadInjectionRules(rulesLocation, keywordsCsv),
            metrics, enabled, strategy);
    }

    /** 构造逻辑提取（单测以合成词表直驱，防装配漂移；敏感词纪律：测试不入真词面） */
    IndirectInjectionScanPostProcessor(
            List<GuardrailRule> injectionRules,
            AiBusinessMetrics metrics,
            boolean enabled,
            String strategy) {
        this.injectionRules = injectionRules;
        this.metrics = metrics;
        this.enabled = enabled;
        boolean exclude = STRATEGY_EXCLUDE.equalsIgnoreCase(strategy);
        if (!exclude && !STRATEGY_WARN.equalsIgnoreCase(strategy)) {
            log.warn("rag.guardrail.indirect.strategy 取值非法（{}），回落缺省策略 warn", strategy);
        }
        this.excludeStrategy = exclude;
        log.info("间接注入扫描装配: enabled={}, 策略={}, 词表 {} 条（同源注入基线）",
            enabled, excludeStrategy ? STRATEGY_EXCLUDE : STRATEGY_WARN, injectionRules.size());
    }

    @Override
    public @NonNull List<Document> process(@NonNull Query query, @NonNull List<Document> documents) {
        if (!enabled || documents.isEmpty() || injectionRules.isEmpty()) {
            return documents;
        }
        List<Document> result = new ArrayList<>(documents.size());
        int flagged = 0;
        int excluded = 0;
        List<String> hitDocIds = new ArrayList<>();
        for (Document doc : documents) {
            if (!injectionHit(doc.getText())) {
                result.add(doc);
                continue;
            }
            flagged++;
            hitDocIds.add(doc.getId());
            if (excludeStrategy) {
                excluded++;
                continue;
            }
            // warn 档：打标保留（新 map 拷贝防共享元数据污染，坑位④ null 禁令）
            Map<String, Object> meta = new HashMap<>(doc.getMetadata());
            meta.put(INDIRECT_HIT_KEY, Boolean.TRUE);
            result.add(Document.builder()
                .id(doc.getId())
                .text(doc.getText())
                .metadata(meta)
                .score(doc.getScore())
                .build());
        }
        if (flagged > 0) {
            metrics.recordIndirectFlagged(flagged);
            log.warn("间接注入扫描命中 {} 条召回证据（策略 {}，文档 ID {}）——逐条警示{}",
                flagged, excludeStrategy ? STRATEGY_EXCLUDE : STRATEGY_WARN, hitDocIds,
                excludeStrategy ? "不适用，已剔除" : "已注入 grounding");
        }
        if (excluded > 0) {
            metrics.recordIndirectExcluded(excluded);
        }
        return result;
    }

    /** 归一化检测视图匹配：BLOCK+FLAG 全档命中即判中（与输入侧检测视图同口径） */
    private boolean injectionHit(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return !TextSanitizer.matchRules(TextSanitizer.normalize(text), injectionRules).isEmpty();
    }
}
