package com.enterprise.kb.etl.transformer;

import com.enterprise.kb.commons.security.TextSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 入库安全消毒转换器（簇② B1；设计文档 12.4 S4 + PII 入库消毒，12.4.2 三道纵深第一道）
 *
 * <p>位置：{@link HtmlProtectingSplitter} 之后、落库之前——kb_chunk / 向量库 / ES
 * 三处存储面消费的都是本转换器的产出：
 *
 * <ul>
 *   <li><b>PII 消毒</b>：chunk 文本落库前掩码（手机/身份证/邮箱，与对话链路
 *       {@code InputSanitizeAdvisor} 经 {@link TextSanitizer} 同源正则）。
 *       MinIO 原件与 TABLE/IMAGE 的 original_content 保留原件——存储脱敏态、
 *       原件随审计访问的合规口径；</li>
 *   <li><b>注入扫描</b>：归一化检测视图命中注入词表 → chunk 元数据打标
 *       {@code injection_hit=true}（落 kb_chunk.metadata JSONB）+ 告警日志，
 *       <b>不阻断入库</b>（12.4.3 S4 定案）——与 S2 Grounding 模板不可信标记
 *       成对构成检索时软防线，打标供运维处置与后续门禁消费。</li>
 * </ul>
 *
 * <p>已知边界（L1 形态，与对话链路对称）：单空格拆词与语义化载荷不设防，
 * 归 12.1.1 L2/L3 升级路线；存量语料不回扫，随重入库窗口（A4/C1）自然消化。
 */
@Slf4j
@Component
public class SanitizingTransformer implements DocumentTransformer {

    /** 注入命中标记键：Document 元数据 → kb_chunk.metadata JSONB */
    public static final String INJECTION_HIT_KEY = "injection_hit";

    private final List<String> injectionKeywords;
    private final boolean piiEnabled;
    private final boolean injectionScanEnabled;

    /**
     * 注入词表与对话链路同源：同一配置项 {@code rag.guardrail.input.injection-keywords}
     * （同 Spring 上下文，单一词表口径）；PII 掩码正则同源于 {@link TextSanitizer}。
     */
    public SanitizingTransformer(
            @Value("${rag.guardrail.input.injection-keywords:}") String keywordsCsv,
            @Value("${kb.etl.sanitize.pii-enabled:true}") boolean piiEnabled,
            @Value("${kb.etl.sanitize.injection-scan:true}") boolean injectionScanEnabled) {
        this.injectionKeywords = TextSanitizer.loadInjectionKeywords(keywordsCsv);
        this.piiEnabled = piiEnabled;
        this.injectionScanEnabled = injectionScanEnabled;
        log.info("ETL 入库消毒装配: pii={}, injectionScan={}, 词表 {} 条",
            piiEnabled, injectionScanEnabled, injectionKeywords.size());
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        List<Document> result = new ArrayList<>(documents.size());
        int piiMasked = 0;
        int injectionHits = 0;

        for (int i = 0; i < documents.size(); i++) {
            Document chunk = documents.get(i);
            String text = chunk.getText();
            if (text == null || text.isEmpty()) {
                result.add(chunk);
                continue;
            }

            Document current = chunk;

            // 1. PII 消毒：掩码落库（幂等正则，掩码形态不二次匹配）
            if (piiEnabled) {
                String masked = TextSanitizer.maskPii(text);
                if (!masked.equals(text)) {
                    current = current.mutate().text(masked).build();
                    text = masked;
                    piiMasked++;
                }
            }

            // 2. 注入扫描：归一化检测视图命中 → 打标不阻断（扫描存储态文本）
            if (injectionScanEnabled
                    && TextSanitizer.containsInjectionKeyword(TextSanitizer.normalize(text), injectionKeywords)) {
                current = current.mutate().metadata(INJECTION_HIT_KEY, true).build();
                injectionHits++;
                log.warn("ETL 注入扫描命中: 第 {} 个 chunk 打标 injection_hit（不阻断入库，S2 模板标记兜底）", i);
            }

            result.add(current);
        }

        if (piiMasked > 0 || injectionHits > 0) {
            log.info("ETL 入库消毒汇总: 共 {} chunk，PII 掩码 {}，注入打标 {}",
                documents.size(), piiMasked, injectionHits);
        }
        return result;
    }
}
