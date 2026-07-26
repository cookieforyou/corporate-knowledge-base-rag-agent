# Spring AI 2.0 企业级项目落地：架构设计、最佳实践与迭代路线图

> **适用场景**：企业级 AI Agent、智能知识库、AI 客服、业务自动化助手等生产级项目
> 
> **技术基座**：Spring Boot 4.1 + Spring AI 2.0.0 GA + Java 21 + 云原生架构
> 
> **报告定位**：从 0 到 1 再到 100 的全生命周期落地指南

---

## 一、企业级 AI 项目的核心挑战与设计原则

在将 Spring AI 2.0 引入企业生产环境时，我们面临的不再是"如何调通 API"，而是以下**六大企业级挑战**：

| 挑战维度 | 具体痛点 | Spring AI 2.0 的破局点 |
|---|---|---|
| **高可用** | 单一模型提供商宕机导致业务瘫痪 | 多模型智能路由 + Fallback 降级 |
| **数据安全** | 用户隐私数据泄露给第三方模型 | Advisor 拦截器链实现出入参脱敏 |
| **幻觉控制** | 模型"一本正经胡说八道" | 高级 RAG 管道 + Grounding 机制 |
| **成本控制** | Token 消耗失控、恶意刷量 | 限流 Advisor + Token 预算管控 |
| **可观测性** | AI 调用黑盒，出问题无法排查 | OpenTelemetry 原生集成 + 全链路追踪 |
| **工具生态** | 内部系统多、接口杂、难统一 | MCP 协议标准化 + ToolCallingManager |

### 企业级设计四大原则

```
1. AI 是基础设施，不是业务代码     → 通过 Spring Bean 注入，与业务解耦
2. 模型是可替换的消耗品           → 面向 ChatModel 接口编程，不绑定厂商
3. 安全合规前置                  → Advisor 拦截器链强制校验，不依赖开发者自觉
4. 一切皆可观测                  → 每次 LLM 调用、Tool 执行、RAG 检索全部埋点
```

---

## 二、系统架构设计：分层与模块化

### 2.1 企业级四层架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                        接入层 (Gateway Layer)                        │
│   API Gateway / BFF / Rate Limiter / Auth / SSE Stream Proxy        │
├─────────────────────────────────────────────────────────────────────┤
│                     AI 编排层 (Orchestration Layer)                  │
│                                                                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────────┐     │
│  │ChatClient│  │ Advisor  │  │  Prompt  │  │  ChatMemory      │     │
│  │(Fluent)  │→ │  Chain   │→ │ Template │→ │ (Redis/Postgres) │     │
│  └──────────┘  └──────────┘  └──────────┘  └──────────────────┘     │
├─────────────────────────────────────────────────────────────────────┤
│                     能力层 (Capability Layer)                        │
│                                                                     │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────────┐    │
│  │ Model Route│ │ToolCalling │ │  RAG Pipe  │ │  MCP Client    │    │
│  │ (多模型路由) │ │  Manager   │ │(检索增强)   │ │ (标准化工具协议) │    │
│  └────────────┘ └────────────┘ └────────────┘ └────────────────┘    │
├─────────────────────────────────────────────────────────────────────┤
│                     基础设施层 (Infrastructure Layer)                 │
│                                                                     │
│  ┌────────┐ ┌──────────┐ ┌─────────┐ ┌────────┐ ┌──────────────┐    │
│  │ OpenAI │ │ Anthropic│ │ Ollama  │ │VectorDB│ │ Observability│    │
│  │  API   │ │   API    │ │ (Local) │ │(Redis/ │ │ (OTel/Prom)  │    │
│  └────────┘ └──────────┘ └─────────┘ │PgVect) │ └──────────────┘    │
│                                      └────────┘                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 微服务拆分建议

对于大型企业项目，建议将 AI 能力拆分为独立微服务，而非塞进现有业务单体：

| 微服务 | 职责 | 技术栈 |
|---|---|---|
| `ai-gateway-service` | 统一接入、限流、鉴权、SSE代理 | Spring Cloud Gateway + Redis |
| `ai-chat-service` | 对话编排、记忆管理、多模型路由 | Spring AI ChatClient + Advisor |
| `ai-rag-service` | 文档解析、向量化、检索增强 | Spring AI VectorStore + ETL |
| `ai-tool-service` | MCP Server 宿主、内部API适配 | Spring AI MCP Server |
| `ai-eval-service` | 模型评估、A/B测试、质量监控 | Spring AI Evaluation |

---

## 三、核心领域设计与代码实战

### 3.1 多模型智能路由与容灾降级 ⭐⭐⭐

**企业痛点**：OpenAI 宕机了怎么办？某个模型 API 限流了怎么办？如何根据任务复杂度自动选择模型（简单问题用便宜模型，复杂问题用贵模型）以控制成本？

**设计方案**：基于 Spring AI 2.0 的 `ChatModel` 接口，实现一个**智能路由 ChatModel**。

```java
/**
 * 企业级多模型智能路由器
 * 支持：优先级路由、故障自动切换、按任务复杂度分流
 */
@Component
public class SmartRoutingChatModel implements ChatModel {

    private final List<ChatModelEntry> modelEntries;
    private final CircuitBreakerFactory circuitBreakerFactory;
    private final MeterRegistry meterRegistry;

    public SmartRoutingChatModel(
            OpenAiChatModel gpt4o,
            OpenAiChatModel gpt4oMini,
            AnthropicChatModel claude,
            OllamaChatModel ollama,
            CircuitBreakerFactory circuitBreakerFactory,
            MeterRegistry meterRegistry) {

        this.circuitBreakerFactory = circuitBreakerFactory;
        this.meterRegistry = meterRegistry;

        // 按优先级注册模型池
        this.modelEntries = List.of(
            new ChatModelEntry("gpt-4o", gpt4o, 1, ModelTier.PREMIUM),
            new ChatModelEntry("claude-sonnet", claude, 2, ModelTier.PREMIUM),
            new ChatModelEntry("gpt-4o-mini", gpt4oMini, 3, ModelTier.STANDARD),
            new ChatModelEntry("ollama-local", ollama, 99, ModelTier.FALLBACK)
        );
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        // 1. 根据 Prompt 复杂度选择模型层级
        ModelTier targetTier = analyzeComplexity(prompt);

        // 2. 获取候选模型列表（按优先级排序）
        List<ChatModelEntry> candidates = modelEntries.stream()
                .filter(e -> e.tier() == targetTier || e.tier() == ModelTier.FALLBACK)
                .sorted(Comparator.comparingInt(ChatModelEntry::priority))
                .toList();

        // 3. 带熔断的故障转移调用
        for (ChatModelEntry entry : candidates) {
            CircuitBreaker cb = circuitBreakerFactory.create(entry.name());
            try {
                ChatResponse response = cb.run(() -> {
                    meterRegistry.counter("ai.model.calls", "model", entry.name()).increment();
                    return entry.model().call(prompt);
                });
                return response;
            } catch (Exception e) {
                meterRegistry.counter("ai.model.failures", "model", entry.name()).increment();
                log.warn("模型 {} 调用失败，尝试降级到下一个: {}", entry.name(), e.getMessage());
            }
        }

        throw new AiFallbackExhaustedException("所有模型均不可用");
    }

    private ModelTier analyzeComplexity(Prompt prompt) {
        // 简单启发式：根据 token 数量和是否包含工具调用判断
        boolean hasTools = prompt.getOptions() != null
                && prompt.getOptions().getToolCallbacks() != null
                && !prompt.getOptions().getToolCallbacks().isEmpty();
        int estimatedTokens = estimateTokens(prompt);

        if (hasTools || estimatedTokens > 4000) {
            return ModelTier.PREMIUM;
        }
        return ModelTier.STANDARD;
    }

    record ChatModelEntry(String name, ChatModel model, int priority, ModelTier tier) {}
    enum ModelTier { PREMIUM, STANDARD, FALLBACK }
}
```

**配置注入：**

```java
@Configuration
public class ModelConfig {

    @Bean
    public OpenAiChatModel gpt4o(OpenAiApi openAiApi) {
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o")
                        .temperature(0.7)
                        .build())
                .build();
    }

    @Bean
    public OpenAiChatModel gpt4oMini(OpenAiApi openAiApi) {
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.7)
                        .build())
                .build();
    }

    @Bean
    public AnthropicChatModel claudeSonnet(AnthropicApi anthropicApi) {
        return AnthropicChatModel.builder()
                .anthropicApi(anthropicApi)
                .defaultOptions(AnthropicChatOptions.builder()
                        .model("claude-sonnet-4-20250514")
                        .build())
                .build();
    }
}
```

---

### 3.2 企业级 Advisor 拦截器链 ⭐⭐⭐

Advisor 是 Spring AI 2.0 最核心的企业级特性。它让你可以用**声明式、非侵入**的方式解决安全、合规、日志、限流等横切关注点。

#### 企业级 Advisor 执行链设计

```
请求流入
  │
  ├─ [1] RateLimitAdvisor        (限流拦截，order=100)
  ├─ [2] AuthAdvisor             (鉴权拦截，order=200)
  ├─ [3] InputSanitizeAdvisor    (输入脱敏/过滤，order=300)
  ├─ [4] MessageChatMemoryAdvisor(记忆注入，order=400)
  ├─ [5] QuestionAnswerAdvisor   (RAG检索注入，order=500)
  ├─ [6] ToolCallingAdvisor      (工具调用循环，order=1000，框架内置最高优先级)
  │
  ▼
 LLM 调用
  │
  ├─ [6] ToolCallingAdvisor      (工具结果回传)
  ├─ [5] QuestionAnswerAdvisor   (RAG后置处理)
  ├─ [4] MessageChatMemoryAdvisor(记忆保存)
  ├─ [3] OutputGuardrailAdvisor  (输出安全护栏，order=300)
  ├─ [2] TokenBudgetAdvisor      (Token计费/预算扣减，order=200)
  └─ [1] AuditLogAdvisor         (审计日志落库，order=100)
  │
  ▼
响应返回
```

#### 实战代码：输入脱敏 Advisor

```java
/**
 * 输入脱敏 Advisor：在发送给 LLM 之前，自动脱敏用户输入中的敏感信息
 * 防止手机号、身份证、银行卡号等 PII 数据泄露给第三方模型
 */
@Component
public class InputSanitizeAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    private final PiiDetector piiDetector;  // 自研或引入 PII 检测库

    public InputSanitizeAdvisor(PiiDetector piiDetector) {
        this.piiDetector = piiDetector;
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        AdvisedRequest sanitized = sanitize(request);
        return chain.nextAroundCall(sanitized);
    }

    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest request, StreamAroundAdvisorChain chain) {
        AdvisedRequest sanitized = sanitize(request);
        return chain.nextAroundStream(sanitized);
    }

    private AdvisedRequest sanitize(AdvisedRequest request) {
        String originalText = request.userText();

        // 检测并替换 PII（个人身份信息）
        String sanitized = piiDetector.detectAndMask(originalText);
        // 例: "我叫张三，手机号13812345678" → "我叫[NAME]，手机号[PHONE]"

        if (!sanitized.equals(originalText)) {
            log.warn("检测到敏感信息并已脱敏, conversationId={}",
                    request.adviseContext().get("chat_memory_conversation_id"));
        }

        return AdvisedRequest.from(request)
                .withUserText(sanitized)
                .build();
    }

    @Override
    public int getOrder() { return 300; }

    @Override
    public String getName() { return "InputSanitizeAdvisor"; }
}
```

#### 实战代码：输出安全护栏 Advisor

```java
/**
 * 输出安全护栏：拦截模型输出中的不当内容、幻觉声明、竞品提及等
 */
@Component
public class OutputGuardrailAdvisor implements CallAroundAdvisor {

    private final List<GuardrailRule> rules;

    public OutputGuardrailAdvisor(
            SensitiveWordRule sensitiveWordRule,
            CompetitorMentionRule competitorRule,
            HallucinationCheckRule hallucinationRule) {
        this.rules = List.of(sensitiveWordRule, competitorRule, hallucinationRule);
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        AdvisedResponse response = chain.nextAroundCall(request);

        String output = response.response().getResult().getOutput().getText();

        for (GuardrailRule rule : rules) {
            GuardrailResult result = rule.check(output);
            if (result.isBlocked()) {
                log.warn("输出被护栏拦截: rule={}, reason={}", rule.getName(), result.getReason());
                // 替换为安全响应
                return replaceWithSafeResponse(response, rule.getFallbackMessage());
            }
            if (result.isModified()) {
                output = result.getModifiedText();
            }
        }

        return AdvisedResponse.from(response)
                .withModifiedOutput(output)
                .build();
    }

    @Override
    public int getOrder() { return 300; }
}
```

#### 实战代码：Token 预算与限流 Advisor

```java
/**
 * 企业级限流 + Token 预算管控
 */
@Component
public class TokenBudgetAdvisor implements CallAroundAdvisor {

    private final TokenBudgetService budgetService;
    private final RedissonClient redisson;

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        String userId = request.adviseContext().get("user_id");

        // 1. 前置检查：用户是否还有预算
        RRateLimiter limiter = redisson.getRateLimiter("ai:budget:" + userId);
        limiter.trySetRate(RateType.OVERALL, 100, 1, RateIntervalUnit.HOURS); // 每小时100次

        if (!limiter.tryAcquire(1)) {
            throw new AiQuotaExceededException("您的 AI 调用配额已用完，请明天再试");
        }

        // 2. 执行调用
        AdvisedResponse response = chain.nextAroundCall(request);

        // 3. 后置扣费：根据实际 Token 消耗扣减预算
        Usage usage = response.response().getMetadata().getUsage();
        long totalTokens = usage.getPromptTokens() + usage.getCompletionTokens();
        budgetService.deductTokens(userId, totalTokens);

        return response;
    }

    @Override
    public int getOrder() { return 200; }
}
```

#### 组装完整 Advisor 链

```java
@Configuration
public class EnterpriseChatClientConfig {

    @Bean
    public ChatClient enterpriseChatClient(
            ChatClient.Builder builder,
            SmartRoutingChatModel routingModel,
            ChatMemory chatMemory,
            VectorStore vectorStore,
            InputSanitizeAdvisor inputSanitize,
            OutputGuardrailAdvisor outputGuardrail,
            TokenBudgetAdvisor tokenBudget,
            AuditLogAdvisor auditLog) {

        return builder
                .defaultModel(routingModel)  // 注入智能路由模型
                .defaultSystem("""
                    你是企业智能助手。请遵循以下规则：
                    1. 只基于提供的知识库内容回答，不确定时明确告知
                    2. 不讨论政治、宗教等敏感话题
                    3. 不提及竞争对手产品
                    4. 回答简洁专业，使用中文
                    """)
                .defaultAdvisors(
                    // 按 order 自动排序执行
                    inputSanitize,           // 输入脱敏
                    outputGuardrail,         // 输出护栏
                    tokenBudget,             // 预算管控
                    auditLog,                // 审计日志
                    new MessageChatMemoryAdvisor(chatMemory),  // 记忆
                    new QuestionAnswerAdvisor(vectorStore,     // RAG
                        SearchRequest.builder()
                            .topK(5)
                            .similarityThreshold(0.75)
                            .build())
                )
                .build();
    }
}
```

---

### 3.3 企业级高级 RAG 管道设计 ⭐⭐⭐

企业级 RAG 不是简单的"查向量库 + 拼 Prompt"，而是一个**多阶段、可编排的管道**。

#### 高级 RAG 架构

```
用户查询
  │
  ├─ Pre-Retrieval（检索前处理）
  │   ├─ 查询改写（Query Rewriting）：用 LLM 优化用户模糊查询
  │   ├─ 查询路由（Query Routing）：判断该查哪个知识库
  │   └─ 查询扩展（HyDE / Multi-Query）：生成多个检索变体
  │
  ├─ Retrieval（检索阶段）
  │   ├─ 向量检索（Vector Search）：语义相似度
  │   ├─ 关键词检索（BM25/Full-text）：精确匹配
  │   └─ 混合检索（Hybrid Search）：向量 + 关键词融合
  │
  ├─ Post-Retrieval（检索后处理）
  │   ├─ 重排序（Reranking）：用 Cross-Encoder 模型精排
  │   ├─ 去重与合并（Dedup & Merge）
  │   └─ 上下文压缩（Context Compression）：裁剪无关内容节省 Token
  │
  └─ Generation（生成阶段）
      └─ Grounding（引用溯源）：标注答案来源文档
```

#### 企业级 RAG 配置实战

```java
@Configuration
public class EnterpriseRagConfig {

    // 1. 多知识库路由：根据查询意图选择不同 VectorStore
    @Bean
    public Map<String, VectorStore> knowledgeStores(EmbeddingModel embeddingModel) {
        return Map.of(
            "product_docs", new PgVectorStore(dataSource, embeddingModel,
                PgVectorStore.PgVectorStoreConfig.builder()
                    .withSchemaName("product")
                    .withIndexName("product_docs_idx")
                    .build()),
            "hr_policy", new RedisVectorStore(
                RedisVectorStore.RedisVectorStoreConfig.builder()
                    .withIndexName("hr_policy_idx")
                    .build(), embeddingModel),
            "tech_wiki", new ChromaVectorStore(embeddingModel,
                ChromaVectorStore.ChromaVectorStoreConfig.builder()
                    .withCollectionName("tech_wiki")
                    .build())
        );
    }

    // 2. 文档 ETL 管道（异步处理）
    @Bean
    public DocumentEtlPipeline etlPipeline(VectorStore vectorStore) {
        return DocumentEtlPipeline.builder()
                // 文档读取：支持 PDF、Word、HTML、Markdown
                .reader(new TikaDocumentReader())
                // 一级切分：按章节/段落
                .splitter(new TokenTextSplitter(800, 200, 5, 10000, true))
                // 元数据增强：自动提取标题、作者、日期
                .transformer(new MetadataEnricher())
                // 向量化并存储
                .writer(vectorStore)
                .build();
    }
}
```

```java
/**
 * 高级 RAG 服务：查询改写 + 混合检索 + 重排序 + 引用溯源
 */
@Service
public class AdvancedRagService {

    private final ChatClient chatClient;
    private final Map<String, VectorStore> knowledgeStores;
    private final RerankModel rerankModel;

    public String askWithAdvancedRag(String userQuery, String conversationId) {

        // Step 1: 查询改写 - 用 LLM 将模糊查询转为精确查询
        String rewrittenQuery = rewriteQuery(userQuery);

        // Step 2: 多知识库混合检索
        List<Document> candidates = new ArrayList<>();
        for (VectorStore store : knowledgeStores.values()) {
            // 向量检索
            candidates.addAll(store.similaritySearch(
                SearchRequest.builder()
                    .query(rewrittenQuery)
                    .topK(10)
                    .similarityThreshold(0.6)
                    .build()));
        }

        // Step 3: 重排序 - 用 Cross-Encoder 精排
        List<Document> reranked = rerankModel.rerank(rewrittenQuery, candidates, 5);

        // Step 4: 构建带引用标记的上下文
        String context = buildGroundedContext(reranked);

        // Step 5: 生成回答（带引用溯源）
        return chatClient.prompt()
                .system("""
                    基于以下参考资料回答问题。
                    规则：
                    1. 每个事实性陈述必须标注来源，格式为 [ref-N]
                    2. 如果资料中没有相关信息，明确说"根据现有资料无法回答"
                    3. 不要编造资料中没有的内容
                    """)
                .user(context + "\n\n用户问题：" + userQuery)
                .advisors(spec -> spec
                    .param("chat_memory_conversation_id", conversationId))
                .call()
                .content();
    }

    private String rewriteQuery(String original) {
        // 用轻量模型改写查询
        return chatClient.prompt()
                .system("将用户的模糊查询改写为精确的检索查询，只输出改写后的查询。")
                .user(original)
                .call()
                .content();
    }

    private String buildGroundedContext(List<Document> docs) {
        StringBuilder sb = new StringBuilder("参考资料：\n");
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            sb.append(String.format("[ref-%d] 来源: %s, 内容: %s\n",
                    i + 1,
                    doc.getMetadata().getOrDefault("source", "unknown"),
                    doc.getText()));
        }
        return sb.toString();
    }
}
```

---

### 3.4 Agent 工具生态：MCP + ToolCallingManager ⭐⭐⭐

#### 企业级工具架构

```
┌─────────────────────────────────────────────────────────┐
│                    ChatClient (Agent)                   │
│                             │                           │
│                    ToolCallingManager                   │
│                    │        │       │                   │
│           ┌────────┘        │       └─────────┐         │
│           ▼                 ▼                 ▼         │
│    ┌─────────────┐  ┌──────────────┐  ┌──────────────┐  │
│    │ @Tool 方法   │  │ MCP Client   │  │ OpenAPI 适配 │  │
│    │ (内部服务)   │  │ (外部MCP服务)  │  │ (REST API)   │  │
│    └─────────────┘  └──────────────┘  └──────────────┘  │
│           │                  │                  │       │
│           ▼                  ▼                  ▼       │
│    ┌─────────────┐  ┌──────────────┐  ┌──────────────┐  │
│    │ 订单系统     │  │ 12306 MCP    │  │ ERP REST API │  │
│    │ 用户系统     │  │ 天气 MCP      │  │ CRM REST API │  │
│    │ 库存系统     │  │ 地图 MCP      │  │ 财务 REST API │  │
│    └─────────────┘  └──────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────┘
```

#### 工具注册最佳实践

```java
/**
 * 企业级工具注册中心
 * 统一管理 @Tool 方法、MCP 工具、OpenAPI 工具
 */
@Configuration
public class ToolRegistryConfig {

    // 1. 内部服务工具（@Tool 注解）
    @Bean
    public ToolCallbackProvider internalTools(
            OrderToolService orderTools,
            UserToolService userTools,
            InventoryToolService inventoryTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(orderTools, userTools, inventoryTools)
                .build();
    }

    // 2. MCP 外部工具（通过 MCP Client 自动发现）
    @Bean
    public ToolCallbackProvider mcpTools(McpSyncClient mcpClient) {
        return new SyncMcpToolCallbackProvider(mcpClient);
    }

    // 3. OpenAPI 适配工具（将 Swagger/OpenAPI 规范转为 Tool）
    @Bean
    public ToolCallbackProvider openApiTools() {
        return OpenApiToolCallbackProvider.builder()
                .openApiSpec(new ClassPathResource("openapi/erp-api.yaml"))
                .baseUrl("https://erp.internal.com/api")
                .authHeader("Authorization", "Bearer " + erpToken)
                .build();
    }

    // 4. 聚合所有工具到 ChatClient
    @Bean
    public ChatClient agentChatClient(
            ChatClient.Builder builder,
            ToolCallbackProvider internalTools,
            ToolCallbackProvider mcpTools,
            ToolCallbackProvider openApiTools) {

        // 合并所有工具回调
        ToolCallback[] allTools = Stream.of(
                internalTools.getToolCallbacks(),
                mcpTools.getToolCallbacks(),
                openApiTools.getToolCallbacks()
        ).flatMap(Arrays::stream).toArray(ToolCallback[]::new);

        return builder
                .defaultSystem("你是企业全能助手，可以操作订单、用户、库存、ERP等系统。")
                .defaultToolCallbacks(allTools)
                .build();
    }
}
```

#### 工具安全控制（关键！）

企业环境中，Agent 调用工具必须有**权限控制和审批机制**：

```java
@Service
public class OrderToolService {

    @Tool(description = "查询订单详情")
    public OrderInfo queryOrder(String orderId) {
        // 只读操作，直接执行
        return orderRepository.findById(orderId);
    }

    @Tool(description = "取消订单（需要审批）")
    public String cancelOrder(String orderId, String reason) {
        // 写操作：需要人工审批（Human-in-the-Loop）
        ApprovalRequest request = new ApprovalRequest(
            "CANCEL_ORDER", orderId, reason, getCurrentUser());
        approvalService.submitForApproval(request);
        return "订单取消请求已提交，等待主管审批。审批单号：" + request.getId();
    }

    @Tool(description = "修改订单金额（高危操作，需二次确认）")
    public String modifyOrderAmount(String orderId, BigDecimal newAmount) {
        // 高危操作：要求用户在对话中二次确认
        if (newAmount.compareTo(BigDecimal.ZERO) < 0) {
            return "金额不能为负数，请确认后重新输入。";
        }
        orderRepository.updateAmount(orderId, newAmount);
        auditLog.record("MODIFY_AMOUNT", orderId, newAmount);
        return "订单金额已修改为 " + newAmount;
    }
}
```

---

### 3.5 可观测性（Observability）⭐⭐⭐

企业级 AI 项目**必须**具备全链路可观测性，否则出了问题根本无法排查。

#### 全链路追踪配置

```yaml
# application.yml
spring:
  ai:
    # 开启 Spring AI 内置的 Micrometer 观测
    chat:
      observations:
        include-input: true    # 生产环境建议关闭（隐私）
        include-output: false
    mcp:
      client:
        observations:
          include-tools: true

management:
  observations:
    enable:
      spring.ai: true
  tracing:
    sampling:
      probability: 1.0  # 开发环境100%采样，生产环境建议0.1
  metrics:
    tags:
      application: ${spring.application.name}
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus,info
```

#### 自定义 AI 指标 Dashboard（Grafana）

Spring AI 2.0 自动暴露以下 Metrics：

| 指标名 | 类型 | 说明 |
|---|---|---|
| `spring.ai.chat.client.duration` | Timer | ChatClient 总调用耗时 |
| `spring.ai.chat.model.duration` | Timer | 模型推理耗时 |
| `spring.ai.chat.model.token.usage` | Counter | Token 消耗量（按 model/prompt/completion 分） |
| `spring.ai.tool.call.duration` | Timer | 工具调用耗时 |
| `spring.ai.vector.store.search.duration` | Timer | 向量检索耗时 |
| `spring.ai.mcp.client.tool.call.duration` | Timer | MCP 工具调用耗时 |

```java
/**
 * 自定义 AI 业务指标
 */
@Component
public class AiBusinessMetrics {

    private final MeterRegistry registry;

    // 记录用户满意度反馈
    public void recordFeedback(String conversationId, boolean positive) {
        registry.counter("ai.user.feedback",
                "conversation_id", conversationId,
                "sentiment", positive ? "positive" : "negative"
        ).increment();
    }

    // 记录 RAG 检索命中率
    public void recordRagHitRate(String knowledgeBase, int hitCount) {
        registry.gauge("ai.rag.hit_count",
                Tags.of("knowledge_base", knowledgeBase),
                hitCount);
    }

    // 记录工具调用成功率
    public void recordToolCall(String toolName, boolean success) {
        registry.counter("ai.tool.call",
                "tool", toolName,
                "status", success ? "success" : "failure"
        ).increment();
    }
}
```

---

## 四、工程化与 DevOps 最佳实践

### 4.1 Prompt 管理：从硬编码到版本化

**反模式**：把 Prompt 写死在 Java 代码里。

**最佳实践**：Prompt 外部化、版本化、支持 A/B 测试。

```java
/**
 * Prompt 模板管理器：支持从数据库/配置中心动态加载
 */
@Service
public class PromptTemplateManager {

    private final PromptTemplateRepository repository;
    private final StringRedisTemplate redis;

    public Prompt getTemplate(String templateId, Map<String, Object> variables) {
        // 1. 先查缓存
        String cached = redis.opsForValue().get("prompt:" + templateId);
        if (cached != null) {
            return new PromptTemplate(cached).create(variables);
        }

        // 2. 查数据库（支持运营人员在线修改）
        PromptTemplateEntity entity = repository.findActiveByTemplateId(templateId);
        redis.opsForValue().set("prompt:" + templateId,
                entity.getContent(), Duration.ofMinutes(5));

        return new PromptTemplate(entity.getContent()).create(variables);
    }
}
```

### 4.2 测试策略：AI 应用的测试金字塔

```
                    ┌────────┐
                    │ E2E测试 │  ← 少量：完整对话场景回归
                   ┌┴─────────┴┐
                   │  集成测试   │  ← 中量：Advisor链 + RAG管道
                  ┌┴────────────┴┐
                  │   单元测试     │  ← 大量：工具方法、解析逻辑
                  └───────────────┘
```

```java
/**
 * ChatClient 集成测试（使用 Spring AI 的 Testcontainers 支持）
 */
@SpringBootTest
@Testcontainers
class EnterpriseChatClientIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Autowired
    private ChatClient chatClient;

    @Test
    void shouldReturnGroundedAnswer() {
        String response = chatClient.prompt()
                .user("公司的年假政策是什么？")
                .call()
                .content();

        // 验证回答基于知识库而非幻觉
        assertThat(response).doesNotContain("我无法回答");
        assertThat(response).containsAnyOf("年假", "休假", "假期");
    }

    @Test
    void shouldSanitizePiiBeforeSending() {
        // 验证脱敏 Advisor 正常工作
        String response = chatClient.prompt()
                .user("帮我查一下手机号13812345678的订单")
                .call()
                .content();

        // 模型不应在回复中重复完整手机号
        assertThat(response).doesNotContain("13812345678");
    }

    @Test
    void shouldFallbackWhenPrimaryModelDown() {
        // 模拟主模型超时，验证降级到备用模型
        // ... 使用 WireMock 模拟 OpenAI API 503
    }
}
```

### 4.3 部署架构：云原生 + 弹性伸缩

```yaml
# Kubernetes Deployment 关键配置
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ai-chat-service
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  template:
    spec:
      containers:
        - name: ai-chat
          image: registry.internal.com/ai-chat:2.0.0
          resources:
            requests:
              memory: "1Gi"
              cpu: "500m"
            limits:
              memory: "2Gi"
              cpu: "1000m"
          # 健康检查
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
          env:
            - name: SPRING_AI_OPENAI_API_KEY
              valueFrom:
                secretKeyRef:
                  name: ai-secrets
                  key: openai-api-key
```

---

## 五、项目迭代路线图（从 0 到 100）

### Phase 1：MVP 阶段（第 1-2 周）—— "能用"

**目标**：快速验证 AI 能力，跑通最小闭环

```
✅ 搭建 Spring Boot 4 + Spring AI 2.0 基础工程
✅ 接入一个模型（OpenAI / Ollama）
✅ 实现基础 ChatClient 对话（单轮 + 多轮记忆）
✅ 暴露 REST API + SSE 流式接口
✅ 基础日志记录（SimpleLoggerAdvisor）
```

**交付物**：一个能对话的 AI 助手 Demo

---

### Phase 2：知识增强阶段（第 3-5 周）—— "有用"

**目标**：接入企业知识库，控制幻觉

```
✅ 搭建 VectorStore（Redis/PgVector）
✅ 实现文档 ETL 管道（PDF/Word → 切分 → 向量化）
✅ 接入 QuestionAnswerAdvisor 实现基础 RAG
✅ 实现查询改写 + 引用溯源
✅ Prompt 外部化管理
✅ 接入多模型路由（主备切换）
```

**交付物**：基于企业知识库的智能问答系统

---

### Phase 3：行动执行阶段（第 6-9 周）—— "能干"

**目标**：让 AI 能操作企业内部系统

```
✅ 实现 @Tool 注解工具（查询订单、用户信息等只读操作）
✅ 搭建 MCP Server 暴露内部 API
✅ 实现 ToolCallingManager 工具调用循环
✅ 加入安全护栏（输入脱敏 + 输出过滤 + 权限控制）
✅ 实现 Token 预算管控 + 限流
✅ 全链路可观测性（OpenTelemetry + Grafana Dashboard）
```

**交付物**：能查能办的企业级 AI Agent

---

### Phase 4：智能化与自治阶段（第 10-16 周）—— "好用"

**目标**：高级 RAG、多 Agent 协作、持续优化

```
✅ 高级 RAG（混合检索 + Reranking + 上下文压缩）
✅ 多 Agent 协作（规划Agent + 执行Agent + 审核Agent）
✅ A/B 测试框架（不同 Prompt/模型的对比评估）
✅ 用户反馈闭环（👍👎 反馈 → 自动标注 → 微调数据集）
✅ 性能优化（Prompt 缓存、语义缓存、流式工具调用）
✅ 安全审计与合规报告
```

**交付物**：生产级、可度量、可进化的 AI 平台

---

## 六、企业级避坑指南（Anti-Patterns）

| # | 反模式 ❌ | 最佳实践 ✅ |
|---|---|---|
| 1 | 直接在 Controller 里 `new ChatClient()` | 通过 `@Bean` 注入，复用连接池和配置 |
| 2 | 把 Prompt 硬编码在 Java 代码中 | 使用 PromptTemplate + 配置中心/数据库管理 |
| 3 | 信任模型输出直接执行写操作 | 写操作必须 Human-in-the-Loop 审批 |
| 4 | 所有请求都用最贵的模型 | 智能路由：简单问题用 mini 模型，复杂问题用 pro |
| 5 | 把原始用户输入直接发给第三方模型 | Advisor 链前置脱敏（PII 过滤） |
| 6 | 向量库只存文本不存元数据 | 必须存储 source、date、author 等元数据用于溯源 |
| 7 | RAG 只检索不重排 | 加入 Reranking 阶段，显著提升准确率 |
| 8 | 没有 Token 预算控制 | 限流 Advisor + 按用户/部门配额 |
| 9 | 用 DEBUG 级别看 AI 日志 | 自定义 INFO 级 AuditLogAdvisor 落库 |
| 10 | 上线后不做评估 | 建立 Eval 数据集，CI/CD 中跑回归测试 |

---

## 七、总结：企业级 Spring AI 2.0 落地 Checklist

```
□ 架构设计
  □ 分层架构（接入/编排/能力/基础设施）
  □ 多模型路由与容灾降级
  □ 微服务拆分（如适用）

□ 安全合规
  □ 输入 PII 脱敏 Advisor
  □ 输出安全护栏 Advisor
  □ 工具调用权限控制 + 审批机制
  □ API Key 加密存储（Spring Vault / K8s Secrets）

□ 数据与 RAG
  □ 文档 ETL 管道（解析 → 切分 → 向量化）
  □ 多知识库路由
  □ 混合检索 + Reranking
  □ 引用溯源标注

□ 工具与 Agent
  □ @Tool 内部工具注册
  □ MCP 外部工具集成
  □ OpenAPI 适配
  □ Human-in-the-Loop 审批流

□ 工程化
  □ Prompt 外部化版本管理
  □ Token 预算 + 限流
  □ OpenTelemetry 全链路追踪
  □ Grafana Dashboard
  □ 集成测试 + Eval 评估集

□ 部署运维
  □ K8s 弹性伸缩
  □ 健康检查 + 优雅停机
  □ SSE 流式代理配置
  □ 日志采集与告警
```

Spring AI 2.0 的核心价值在于：**它让 AI 应用的开发从"手工作坊"走向了"工业化生产线"**。通过 Advisor 拦截器链、模块化架构、MCP 标准化协议和原生可观测性，Java 开发者终于可以用最熟悉的 Spring 范式，构建出安全、可控、可度量的企业级 AI 应用。

> **最后的建议**：不要试图一次性构建完美的 AI 系统。遵循 Phase 1→4 的迭代路线，每个阶段都有可交付的价值，在真实用户反馈中持续进化，才是企业级 AI 项目成功的正确路径。



