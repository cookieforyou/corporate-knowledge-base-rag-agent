package com.enterprise.kb.ai.config;

import com.enterprise.kb.ai.advisor.QueryRoutingAdvisor;
import com.enterprise.kb.ai.advisor.RetrievalGateAdvisor;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.prompt.PromptTemplates;
import com.enterprise.kb.ai.retriever.GraphDocumentRetriever;
import com.enterprise.kb.ai.retriever.HybridDocumentRetriever;
import com.enterprise.kb.ai.retriever.IndirectInjectionScanPostProcessor;
import com.enterprise.kb.ai.retriever.RerankDocumentPostProcessor;
import com.enterprise.kb.ai.retriever.RewriteCapturingQueryTransformer;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import com.enterprise.kb.infrastructure.graph.GraphGateway;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationConvention;
import org.springframework.ai.chat.client.observation.ChatClientObservationConvention;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 检索组件 + RetrievalAugmentationAdvisor 装配（设计文档 10.6，任务 2.10）
 *
 * <p>替代 Phase 1 的 QuestionAnswerAdvisor：查询改写（默认开）→ 双路混合检索 →
 * RRF 融合 → qwen3-rerank 精排 → Grounding 证据注入。kb-eval 注入的
 * {@code chatClient} Bean 名不变，被测链路切换对评估器零感知。
 */
@Configuration
@EnableConfigurationProperties({RetrievalProperties.class, GraphRetrievalProperties.class})
public class RetrievalConfig {

    /*
     * Grounding Prompt（设计文档 10.6 + 11.1.2）：模板文本收编于
     * PromptTemplates#GROUNDING_PROMPT（4.8 Git Ops 外部化，簇⑦ 批2）。
     * 装配语义：{context} 经 {@link #formatNumberedContext} 编号化渲染，[ref-N] 顺序
     * = 重排后 Top-N 排名，与 SSE TRACE 溯源列表下标对齐（2.11/2.12）；
     * {@code <untrusted_context>} 不可信数据标记为 RAG 间接注入软防线（12.4.2 第二道纵深）。
     */

    /**
     * 证据编号化格式器（v2.15 修正，2026-08-09）：每条资料前缀独立的 [ref-N] 编号行
     * （N = 1 起始的列表下标）。
     *
     * <p>缺陷背景（3.17 E2E 发现）：ContextualQueryAugmenter 默认 documentFormatter
     * 仅以换行拼接文档文本、**不编号**（spring-ai-rag 2.0.0 源码核验）——模型面对无编号
     * 拼接文本只能猜测引用编号：或张冠李戴，或越界引用（Top-K=5 却出现 [ref-6]），或抄用
     * 文档正文里的序号符号（DDD 文档圈号标题「⑤」被抄成 [ref-⑤]，前端 ASCII 正则不匹配
     * 致徽标不渲染、引用不可点）。显式编号使引用锚点确定：编号顺序与
     * RerankDocumentPostProcessor 的 final trace 序列一一对应（SSE TRACE / 前端
     * chunks[N-1] 对齐关系不变，11.1.2）。
     *
     * <p><b>元数据感知（安全簇④ D1）</b>：携带
     * {@link IndirectInjectionScanPostProcessor#INDIRECT_HIT_KEY} 标记的证据
     * （warn 策略命中）在编号行后追加 {@link PromptTemplates#INDIRECT_WARNING_NOTE}
     * 逐条警示行；零标记渲染结果与 D1 前逐字一致（零漂移回归钉死）。
     */
    static String formatNumberedContext(List<Document> documents) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            sb.append("[ref-").append(i + 1).append("]\n");
            if (Boolean.TRUE.equals(document.getMetadata().get(IndirectInjectionScanPostProcessor.INDIRECT_HIT_KEY))) {
                sb.append(PromptTemplates.INDIRECT_WARNING_NOTE).append("\n");
            }
            sb.append(document.getText()).append("\n\n");
        }
        return sb.toString();
    }

    /*
     * 空证据拒绝模板（2.10 设计修正）：模板文本收编于
     * PromptTemplates#EMPTY_CONTEXT_PROMPT（4.8 Git Ops 外部化，簇⑦ 批2）。
     * 装配语义：allowEmptyContext=false 时渲染该模板输出确定性拒绝——库外问题
     * 规范拒答（16.4 Negative Rejection ≥ 0.85）的关键机制。模板无占位符，
     * 经无参 render() 调用（RetrievalConfigContextFormatTest 回归钉死）。
     */

    /*
     * 模块化 RAG 主 Advisor（10.6）：改写 → 双路检索 → 融合 → 精排 → 证据注入。
     * Order 500：RetrievalTraceAdvisor(450) 之后、工具调用(1000) 之前（11.2 链序表）。
     *
     * <p>多查询扩展默认关闭：检索与 embedding 调用放大 N 倍，对 TTFT（目标 < 1.5s）
     * 不友好；RRF 融合结构已为扩展留好 DocumentJoiner 接口，需要时开配置即可（10.6）。
     * 簇① A1 A/B 实证（2026-08-11，kb-eval chain 探针）：开启净增益 MRR +0.025 /
     * Recall +0.006，不抵 TTFT 代价，维持默认关——决策全文见 RetrievalProperties.Expansion。
     *
     * <p>检索在 before() 内经 taskExecutor 并行执行（源码核验）；租户/溯源上下文
     * 经 Advisor 参数随 Query.context 流入检索组件——与线程模型解耦，同步/流式一致。
     */

    /**
     * 查询改写器（多轮指代消解）——独立 Bean 以便检索调试台（2.14）复用，
     * 与主链路共享同一实例。
     *
     * <p>簇④ A5：实现由 {@code RewriteQueryTransformer} 切换为
     * {@link CompressionQueryTransformer}——前者默认模板**不消费对话历史**
     * （源码核验：transform 仅传 query/target 参数），指代消解隐式依赖
     * QueryRoutingAdvisor(440) 合并调用顺带完成，路由关闭/分类 fail-open 的
     * 回落路径追问（「它的价格呢」）无法消解；Compression 形态经
     * {@code Query.history()}（RetrievalAugmentationAdvisor 取自
     * prompt.getInstructions()，含 MessageChatMemoryAdvisor 注入的历史）
     * 显式消解。与 440 预写机制零冲突：rewrittenQuery 已预写时
     * RewriteCapturingQueryTransformer 直接复用，本 Bean 不被调用（零重复 LLM）。
     * Bean 返回接口类型（调试台经 default apply() 调用不受影响）。
     *
     * <p><b>轻任务模型挂备（v2.77 模型层批B）</b>：改写 LLM 调用切换到备用模型
     * （qwen3.8-flash 思考关）——见 {@link #lightweightChatClientBuilder}。
     */
    @Bean
    public QueryTransformer rewriteQueryTransformer(
            ChatModel smartRoutingChatModel,
            @Nullable @Qualifier("fallbackChatModel") ChatModel fallbackChatModel,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            ObjectProvider<ChatClientObservationConvention> clientConventionProvider,
            ObjectProvider<AdvisorObservationConvention> advisorConventionProvider) {
        return CompressionQueryTransformer.builder()
            .chatClientBuilder(lightweightChatClientBuilder(smartRoutingChatModel, fallbackChatModel,
                observationRegistryProvider, clientConventionProvider, advisorConventionProvider))
            .promptTemplate(new PromptTemplate(PromptTemplates.HISTORY_REWRITE_PROMPT))
            .build();
    }

    @Bean
    public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(
            ChatModel smartRoutingChatModel,
            @Nullable @Qualifier("fallbackChatModel") ChatModel fallbackChatModel,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            ObjectProvider<ChatClientObservationConvention> clientConventionProvider,
            ObjectProvider<AdvisorObservationConvention> advisorConventionProvider,
            HybridDocumentRetriever hybridRetriever,
            IndirectInjectionScanPostProcessor indirectInjectionScanPostProcessor,
            RerankDocumentPostProcessor rerankPostProcessor,
            QueryTransformer rewriteQueryTransformer,
            @Qualifier("retrievalExecutor") TaskExecutor retrievalExecutor,
            RetrievalProperties properties,
            @Value("${rag.retrieval.rewrite.enabled:true}") boolean rewriteEnabled) {

        RetrievalProperties.Expansion expansion = properties.getExpansion();

        RetrievalAugmentationAdvisor.Builder builder = RetrievalAugmentationAdvisor.builder()
            .documentRetriever(hybridRetriever)
            // 后处理器序列定案（安全簇④ D1）：间接注入扫描置于 rerank 之前——
            // exclude 剔除的 chunk 不参与重排、不进 final TRACE，三面对齐不破
            .documentPostProcessors(indirectInjectionScanPostProcessor, rerankPostProcessor)
            .queryAugmenter(ContextualQueryAugmenter.builder()
                .promptTemplate(new PromptTemplate(PromptTemplates.GROUNDING_PROMPT))
                .emptyContextPromptTemplate(new PromptTemplate(PromptTemplates.EMPTY_CONTEXT_PROMPT))
                // 编号化证据（v2.15）：[ref-N] 锚点与 final trace 序列对齐，修复引用编号漂移
                .documentFormatter(RetrievalConfig::formatNumberedContext)
                .allowEmptyContext(false)
                .build())
            .taskExecutor(retrievalExecutor)
            .order(500);

        if (rewriteEnabled) {
            // 装饰器捕获改写文本供审计落库（3.12）；调试台直注原 Bean 不受影响
            builder.queryTransformers(new RewriteCapturingQueryTransformer(rewriteQueryTransformer));
        }
        if (expansion.isEnabled()) {
            builder.queryExpander(MultiQueryExpander.builder()
                .chatClientBuilder(lightweightChatClientBuilder(smartRoutingChatModel, fallbackChatModel,
                    observationRegistryProvider, clientConventionProvider, advisorConventionProvider))
                .numberOfQueries(expansion.getNumQueries())
                .build());
        }
        return builder.build();
    }

    /**
     * 轻任务 ChatClient.Builder（v2.77 模型层批B）：意图路由(440)/查询改写/多查询
     * 扩展的 LLM 调用统一挂备用模型（qwen3.8-flash 思考关，L2 二判同款载体）——
     * GLM-5.3-Flash 主答强制思考（effort 缺省 max），路由/改写是每请求检索前的
     * 硬前置，走主模型等于每问先烧一段思维链，TTFT 不可接受；改写模型恒定后
     * 主模型切换（provider 开关）不再引起检索形态漂移。备用缺席（单模型形态）
     * 回落主链 ChatModel；kb-eval IT 桩上下文按 @Primary 解析到桩——形态自动正确。
     *
     * <p>局部构造不注册 Bean：自动配置 ChatClient.Builder 挂
     * {@code @ConditionalOnMissingBean}，注册自定义 Builder Bean 会顶掉全局默认，
     * rag/tool 链全部断供（ChatClientAutoConfiguration 源码核验）。观测四参对齐：
     * registry + 双 convention（@Nullable——DefaultChatClientBuilder 源码核验容忍
     * null），轻调用 span 保持入 Langfuse trace 树（簇① 合树形态不回退）。
     */
    private static ChatClient.Builder lightweightChatClientBuilder(
            ChatModel smartRoutingChatModel,
            @Nullable ChatModel fallbackChatModel,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            ObjectProvider<ChatClientObservationConvention> clientConventionProvider,
            ObjectProvider<AdvisorObservationConvention> advisorConventionProvider) {
        ChatModel target = fallbackChatModel != null ? fallbackChatModel : smartRoutingChatModel;
        return ChatClient.builder(target,
            observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP),
            clientConventionProvider.getIfAvailable(),
            advisorConventionProvider.getIfAvailable());
    }

    /**
     * Advisor 内部并行执行器：虚拟线程（与 ETL/检索路径技术栈一致）。
     *
     * <p><b>上下文传递包裹（Phase 4 簇②，簇① trace 碎片化留档修复）</b>：RAA 经本执行器
     * 提交检索任务，裸虚拟线程不继承请求线程的当前观测——rerank/embedding 观测寻父落空
     * 成独立 trace 根。经 {@link ContextPropagatingTaskDecorator} 包裹：提交线程捕获
     * 快照（含 {@code micrometer.observation}，坑位㉖ 静态自动注册的 accessor），
     * 任务线程 restore 开 scope（ObservationThreadLocalAccessor.setValue = openScope，
     * 源码核验），检索任务内 rerank 观测得以挂回 Advisor 树。无当前观测的入口
     * （kb-eval / 检索调试台）捕获为空快照，行为不变。
     */
    @Bean
    public AsyncTaskExecutor retrievalExecutor() {
        return contextPropagatingRetrievalExecutor();
    }

    /**
     * 混合检索双路并行执行器（v2.19 簇③ D2）：此前 HybridDocumentRetriever 每请求
     * {@code new} 虚拟线程 executor——收编为共享 Bean（与 etlExecutor 同形态），
     * 消除高频请求下的重复创建/关闭开销。
     *
     * <p><b>上下文传递包裹（Phase 4 簇②）</b>：嵌套二级提交同样逃逸——向量路
     * embedding 观测发生在二级任务内。{@link ContextExecutorService#wrap} 对
     * submit/execute 全形态捕获-恢复，embedding 观测挂回检索任务上下文
     * （其上下文已由 retrievalExecutor 装饰器 restore，两级串联成链）。
     */
    @Bean(destroyMethod = "close")
    public ExecutorService hybridRetrievalExecutor() {
        return contextPropagatingHybridExecutor();
    }

    /**
     * Graph 路检索器（簇④ 5.2，三路融合第三路）——条件装配：
     * {@code rag.graph.enabled=true} 才在场，{@link HybridDocumentRetriever} 经
     * {@code ObjectProvider} 容忍缺位；关闭态双路链形态逐字节不变（同缓存族纪律）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "rag.graph", name = "enabled", havingValue = "true")
    public GraphDocumentRetriever graphDocumentRetriever(
            GraphGateway graphGateway,
            EmbeddingModel embeddingModel,
            KbChunkRepository chunkRepository,
            KbDocumentRepository documentRepository,
            GraphRetrievalProperties graphRetrievalProperties,
            AiBusinessMetrics metrics,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        return new GraphDocumentRetriever(graphGateway, embeddingModel,
            chunkRepository, documentRepository, graphRetrievalProperties,
            metrics, observationRegistryProvider);
    }

    /** retrievalExecutor 构造逻辑提取（单测直调同构实例，防装配漂移） */
    static AsyncTaskExecutor contextPropagatingRetrievalExecutor() {
        VirtualThreadTaskExecutor delegate = new VirtualThreadTaskExecutor("retrieval-");
        TaskDecorator decorator = new ContextPropagatingTaskDecorator();
        return new AsyncTaskExecutor() {
            @Override
            public void execute(Runnable task) {
                delegate.execute(decorator.decorate(task));
            }
        };
    }

    /** hybridRetrievalExecutor 构造逻辑提取（单测直调同构实例，防装配漂移） */
    static ExecutorService contextPropagatingHybridExecutor() {
        return ContextExecutorService.wrap(
            Executors.newVirtualThreadPerTaskExecutor(), ContextSnapshotFactory.builder().build()::captureAll);
    }

    /**
     * 意图分类 Advisor（5.4 收窄版提前落地，设计文档 11.4）——Order 440。
     * 闲聊/对话元问题置 skipRetrieval 免检索直答；知识问把分类+指代消解合并
     * 产出的改写文本预写入 RetrievalContext（下游改写装饰器跳过二次 LLM 调用）。
     * fail-open：分类故障回落完整检索，{@code enabled=false} 整体回退现状。
     * 分类器经轻任务 Builder 构建（v2.77 挂备用模型，见
     * {@link #lightweightChatClientBuilder}）。
     */
    @Bean
    public QueryRoutingAdvisor queryRoutingAdvisor(
            ChatModel smartRoutingChatModel,
            @Nullable @Qualifier("fallbackChatModel") ChatModel fallbackChatModel,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            ObjectProvider<ChatClientObservationConvention> clientConventionProvider,
            ObjectProvider<AdvisorObservationConvention> advisorConventionProvider,
            ChatMemory agentChatMemory,
            AiBusinessMetrics metrics,
            @Value("${rag.routing.intent.enabled:true}") boolean enabled,
            @Value("${rag.routing.intent.history-size:6}") int historySize) {
        return new QueryRoutingAdvisor(
            lightweightChatClientBuilder(smartRoutingChatModel, fallbackChatModel,
                observationRegistryProvider, clientConventionProvider, advisorConventionProvider),
            agentChatMemory, metrics, enabled, historySize);
    }

    /**
     * 检索门控 Advisor（5.4 收窄版）——Order 500，组合式包裹
     * {@link RetrievalAugmentationAdvisor}（final 类不可 extends，源码核验）：
     * skipRetrieval=true 时 chain 直接放行旁路整套 RAG 管线。rag 链挂本 Bean
     * 替代原 RetrievalAugmentationAdvisor 直挂。
     */
    @Bean
    public RetrievalGateAdvisor retrievalGateAdvisor(
            RetrievalAugmentationAdvisor retrievalAugmentationAdvisor) {
        return new RetrievalGateAdvisor(retrievalAugmentationAdvisor);
    }
}
