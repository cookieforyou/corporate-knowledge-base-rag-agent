package com.enterprise.kb.ai.config;

import com.enterprise.kb.ai.retriever.HybridDocumentRetriever;
import com.enterprise.kb.ai.retriever.RerankDocumentPostProcessor;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.constant.Constants;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 检索组件 + RetrievalAugmentationAdvisor 装配（设计文档 10.6，任务 2.10）
 *
 * <p>替代 Phase 1 的 QuestionAnswerAdvisor：查询改写（默认开）→ 双路混合检索 →
 * RRF 融合 → qwen3-rerank 精排 → Grounding 证据注入。kb-eval 注入的
 * {@code chatClient} Bean 名不变，被测链路切换对评估器零感知。
 */
@Configuration
public class RetrievalConfig {

    /**
     * Grounding Prompt（设计文档 10.6 + 11.1.2）：强制证据约束 + [ref-N] 标注。
     * N 与 ContextualQueryAugmenter 注入证据的顺序一一对应（即重排后 Top-N 排名），
     * 与 SSE TRACE 事件的溯源列表下标对齐（2.11/2.12）。
     *
     * <p>v2 实现注：设计稿模板仅含 {context}——实现核验发现 augment 渲染同时传入
     * query/context 两个参数，模板缺 {query} 会丢失用户问题，已补全。
     */
    private static final String GROUNDING_PROMPT = """
        你是企业知识库专家。必须且只能基于【参考资料】回答问题。

        【回答规则】
        1. 参考资料按相关度从高到低排列，引用第 N 条资料时以 [ref-N] 标注来源
        2. 资料包含相关信息时准确回答，每个事实性陈述附 [ref-N] 标注
        3. 信息不足时说明已有信息并指出缺失部分
        4. 禁止编造、猜测或使用外部知识

        【参考资料】
        {context}

        【用户问题】
        {query}
        """;

    /**
     * 空证据拒绝模板（2.10 设计修正）。核验 ContextualQueryAugmenter 源码语义：
     * allowEmptyContext=true 时空证据会**原样返回用户问题**（模型凭自身知识作答，
     * 负向用例必然幻觉）；=false 时渲染本模板，输出确定性拒绝——库外问题规范拒答
     * （16.4 Negative Rejection ≥ 0.85）的关键机制。本模板经无参 render() 调用，
     * 不得含变量占位符。
     */
    private static final String EMPTY_CONTEXT_PROMPT = """
        知识库中未检索到与用户问题相关的任何内容。禁止依据自身知识作答。
        请直接且仅输出以下回复：
        知识库中未找到相关信息，建议您补充相关文档或换个方式提问。
        """;

    /**
     * 向量路检索器 —— topK 固化为 recallSize（VectorStoreDocumentRetriever 无实例级
     * withTopK，构建期定型）；安全过滤表达式按请求从 RetrievalContext 动态读取
     */
    @Bean
    public VectorStoreDocumentRetriever vectorStoreDocumentRetriever(
            VectorStore vectorStore,
            ObjectProvider<RetrievalContext> retrievalContextProvider) {
        return VectorStoreDocumentRetriever.builder()
            .vectorStore(vectorStore)
            .similarityThreshold(0.5)
            .topK(Constants.DEFAULT_TOP_K * 2)
            .filterExpression(() -> {
                // 非 Web 上下文（kb-eval 等）无请求作用域，降级为不过滤
                if (RequestContextHolder.getRequestAttributes() == null) {
                    return null;
                }
                try {
                    RetrievalContext ctx = retrievalContextProvider.getObject();
                    return ctx != null ? ctx.getSecurityFilter() : null;
                } catch (Exception e) {
                    return null;
                }
            })
            .build();
    }

    /**
     * 模块化 RAG 主 Advisor（10.6）：改写 → 双路检索 → 融合 → 精排 → 证据注入。
     * Order 500：RetrievalTraceAdvisor(450) 之后、工具调用(1000) 之前（11.2 链序表）。
     *
     * <p>多查询扩展默认关闭：检索与 embedding 调用放大 N 倍，对 TTFT（目标 < 1.5s）
     * 不友好；RRF 融合结构已为扩展留好 DocumentJoiner 接口，需要时开配置即可（10.6）。
     */
    @Bean
    public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(
            ChatClient.Builder chatClientBuilder,
            HybridDocumentRetriever hybridRetriever,
            RerankDocumentPostProcessor rerankPostProcessor,
            @Qualifier("retrievalExecutor") TaskExecutor retrievalExecutor,
            @Value("${rag.retrieval.rewrite.enabled:true}") boolean rewriteEnabled,
            @Value("${rag.retrieval.expansion.enabled:false}") boolean expansionEnabled) {

        RetrievalAugmentationAdvisor.Builder builder = RetrievalAugmentationAdvisor.builder()
            .documentRetriever(hybridRetriever)
            .documentPostProcessors(rerankPostProcessor)
            .queryAugmenter(ContextualQueryAugmenter.builder()
                .promptTemplate(new PromptTemplate(GROUNDING_PROMPT))
                .emptyContextPromptTemplate(new PromptTemplate(EMPTY_CONTEXT_PROMPT))
                .allowEmptyContext(false)
                .build())
            .taskExecutor(retrievalExecutor)
            .order(500);

        if (rewriteEnabled) {
            builder.queryTransformers(RewriteQueryTransformer.builder()
                .chatClientBuilder(chatClientBuilder)
                .build());
        }
        if (expansionEnabled) {
            builder.queryExpander(MultiQueryExpander.builder()
                .chatClientBuilder(chatClientBuilder)
                .numberOfQueries(3)
                .build());
        }
        return builder.build();
    }

    /** Advisor 内部并行执行器：虚拟线程（与 ETL/检索路径技术栈一致） */
    @Bean
    public TaskExecutor retrievalExecutor() {
        return new VirtualThreadTaskExecutor("retrieval-");
    }
}
