package com.enterprise.kb.etl.transformer;

import com.enterprise.kb.domain.enums.ChunkType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 上下文增强器（设计文档 9.5，任务 2.4 复活；簇④ A4）——可选环节，置于
 * {@link HtmlProtectingSplitter} 切分与入库消毒之后、落库向量化之前。
 *
 * <p>Anthropic Contextual Retrieval：embedding 前为每个 Chunk 生成一段
 * 「文档级语境说明」前缀，将 Chunk 放回文档语境（官方报告检索失败率降 35-67%）。
 * 对纯表格 Chunk（dm-13 类靶点）收益最直接——表格 HTML 自身语义稀薄，
 * 语境前缀是其 embedding 唯一的主题信号。
 *
 * <p>默认关闭（{@code kb.etl.contextual.enabled=false}）：启用与否经 kb-eval
 * 双探针 A/B 快照决策（全量重入库窗口，报告 §8.3 簇④ A4 决策流程）。
 *
 * <p>数据流：{@code content} 存增强文本（「【上下文】」前缀 + 原文，参与
 * embedding/BM25），{@code original_content} 经 {@link #ORIGINAL_TEXT_KEY}
 * 元数据存原文（前端展示/结构保真）——9.5 数据模型契合。
 *
 * <p>位置在 {@link SanitizingTransformer} 之后的纪律：LLM 只看到脱敏态文本，
 * 原文 PII 不出库（与簇② B1 纵深一致）。
 *
 * <p>容错：单 chunk 生成失败仅 WARN 并原样放行（增强是质量项，不阻断入库）；
 * IMAGE chunk（正文为 img 标签无语义）与超短 chunk 跳过。
 *
 * <p>并发（2026-08-12 优化）：每 chunk 一次 LLM 调用，串行形态下大文档 ETL
 * 时长 = chunk 数 × 单调用时长（实测数十 chunk 即分钟级阻塞上传响应）。
 * 改虚拟线程有界并发（{@code kb.etl.contextual.concurrency} 默认 8，信号量限流
 * 防供应商侧 429），保序返回、单 chunk 失败隔离语义不变。
 *
 * <p>装配：经济模型 deepseek-v4-flash 手工装配 OpenAI 兼容形态
 * （同 SmartRoutingConfig 形态；kb-etl 不依赖 kb-ai-core，避免拖入对话链路
 * Advisor 栈——引 spring-ai-openai 实现模块而非 starter，免自动装配面）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "kb.etl.contextual", name = "enabled", havingValue = "true")
public class ContextualEnrichmentTransformer implements DocumentTransformer {

    /** 文档概要键：DocumentEtlService 切分前写入 chunk 元数据，本转换器消费后移除 */
    public static final String DOC_EXCERPT_KEY = "doc_excerpt";

    /** 原文键：增强后原文经此键流转，DocumentEtlService 落库 kb_chunk.original_content */
    public static final String ORIGINAL_TEXT_KEY = "original_text";

    /** 增强文本前缀（与 9.5 设计一致） */
    static final String ENRICHMENT_PREFIX = "【上下文】";

    /** 低于此长度的 chunk 视为噪声，不值得一次 LLM 调用 */
    private static final int MIN_ENRICH_CHARS = 20;

    private static final String CONTEXT_PROMPT = """
        <document>
        %s
        </document>

        请用 50-100 字说明下面这个片段在文档中的位置与作用（涉及什么主题、与上下文的关系），
        只输出说明文本：
        <chunk>
        %s
        </chunk>
        """;

    private final ChatModel chatModel;
    private final int chunkMaxChars;
    /** 虚拟线程执行器（单例 Bean 持有，非每请求 new——簇③ D2 执行器纪律同构） */
    private final ExecutorService enrichmentExecutor;
    /** 在飞 LLM 调用上限（防供应商限流 429；虚拟线程本身无界，须显式闸门） */
    private final Semaphore concurrencyGate;
    private final int maxConcurrency;

    @Autowired
    public ContextualEnrichmentTransformer(
            @Value("${spring.ai.deepseek.api-key:}") String apiKey,
            @Value("${spring.ai.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${spring.ai.deepseek.chat.model:deepseek-v4-flash}") String model,
            @Value("${kb.etl.contextual.chunk-max-chars:2000}") int chunkMaxChars,
            @Value("${kb.etl.contextual.concurrency:8}") int concurrency) {
        this(buildContextModel(apiKey, baseUrl, model), chunkMaxChars, concurrency);
    }

    /** 测试入口：注入桩 ChatModel（默认并发 8，与生产缺省一致） */
    ContextualEnrichmentTransformer(ChatModel chatModel, int chunkMaxChars) {
        this(chatModel, chunkMaxChars, 8);
    }

    ContextualEnrichmentTransformer(ChatModel chatModel, int chunkMaxChars, int concurrency) {
        this.chatModel = chatModel;
        this.chunkMaxChars = chunkMaxChars;
        this.maxConcurrency = Math.max(1, concurrency);
        this.enrichmentExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.concurrencyGate = new Semaphore(maxConcurrency);
    }

    @PreDestroy
    void shutdown() {
        enrichmentExecutor.close();
    }

    /**
     * 语境生成模型手工装配（经济模型 + 低温度求稳定 + maxTokens 封顶成本）。
     * 显式启用增强但密钥缺失 → 快失败（与 SmartRoutingConfig 主模型同纪律）。
     */
    private static ChatModel buildContextModel(String apiKey, String baseUrl, String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "kb.etl.contextual.enabled=true 但 DEEPSEEK_API_KEY 未配置——语境增强不可用");
        }
        log.info("语境增强模型装配: model={}, baseUrl={}", model, baseUrl);
        return OpenAiChatModel.builder()
            .options(OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(model)
                .temperature(0.0)
                .maxTokens(300)
                .build())
            .build();
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        int n = documents.size();
        Document[] slots = new Document[n];
        AtomicInteger enriched = new AtomicInteger(), skipped = new AtomicInteger(), failed = new AtomicInteger();
        List<CompletableFuture<Void>> inFlight = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            final Document chunk = documents.get(i);
            final int slot = i;
            String text = chunk.getText();
            boolean image = ChunkType.IMAGE.name().equals(String.valueOf(chunk.getMetadata().get("chunk_type")));
            if (image || text == null || text.strip().length() < MIN_ENRICH_CHARS) {
                slots[slot] = stripExcerpt(chunk);
                skipped.incrementAndGet();
                continue;
            }
            // LLM 调用经虚拟线程有界并发分发；槽位按输入下标写入 → 保序返回
            inFlight.add(CompletableFuture.runAsync(() -> {
                try {
                    concurrencyGate.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    slots[slot] = stripExcerpt(chunk);
                    failed.incrementAndGet();
                    return;
                }
                try {
                    slots[slot] = enrichOne(chunk, text, enriched, skipped, failed);
                } finally {
                    concurrencyGate.release();
                }
            }, enrichmentExecutor));
        }
        CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new)).join();

        log.info("语境增强汇总: 共 {} chunk，增强 {}，跳过 {}，失败 {}（并发上限 {}）",
            n, enriched.get(), skipped.get(), failed.get(), maxConcurrency);
        return new ArrayList<>(Arrays.asList(slots));
    }

    /** 单 chunk 增强（并发工作单元）：失败 WARN 原样放行，隔离语义与串行版逐位一致 */
    private Document enrichOne(Document chunk, String text,
                                AtomicInteger enriched, AtomicInteger skipped, AtomicInteger failed) {
        String excerpt = chunk.getMetadata().get(DOC_EXCERPT_KEY) instanceof String s ? s : "";
        try {
            String context = generateContext(excerpt, text);
            if (context == null || context.isBlank()) {
                skipped.incrementAndGet();
                return stripExcerpt(chunk);
            }
            Map<String, Object> meta = new HashMap<>(chunk.getMetadata());
            meta.remove(DOC_EXCERPT_KEY);
            meta.put(ORIGINAL_TEXT_KEY, text);
            enriched.incrementAndGet();
            return Document.builder()
                .text(ENRICHMENT_PREFIX + context.strip() + "\n\n" + text)
                .metadata(meta)
                .build();
        } catch (Exception e) {
            log.warn("语境增强失败（原样放行不阻断 ETL）: {}", e.getMessage());
            failed.incrementAndGet();
            return stripExcerpt(chunk);
        }
    }

    private String generateContext(String excerpt, String chunkText) {
        String promptText = CONTEXT_PROMPT.formatted(excerpt, truncate(chunkText, chunkMaxChars));
        ChatResponse response = chatModel.call(new Prompt(promptText));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    /** 移除文档概要键（未增强路径同样不得把大段概要带入下游元数据面） */
    private static Document stripExcerpt(Document chunk) {
        if (!chunk.getMetadata().containsKey(DOC_EXCERPT_KEY)) {
            return chunk;
        }
        Map<String, Object> meta = new HashMap<>(chunk.getMetadata());
        meta.remove(DOC_EXCERPT_KEY);
        return Document.builder().text(chunk.getText()).metadata(meta).build();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
