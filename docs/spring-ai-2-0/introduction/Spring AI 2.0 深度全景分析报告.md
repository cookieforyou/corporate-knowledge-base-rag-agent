# Spring AI 2.0 深度全景分析报告

> **版本**：Spring AI 2.0.0 GA（2026年6月12日正式发布）
> 
> **基座**：Spring Boot 4.1.0 + Spring Framework 7.0 + Jakarta EE 11 + Java 21（强制）
> 
> **历时**：8 个里程碑版本（M1~M8）+ 2 个候选版本（RC1~RC2），历时半年多

---

## 一、版本定位与发布背景

### 1.1 这不只是版本迭代，而是"推倒重建"

Spring AI 2.0 是 Spring AI 项目自 1.0.0 GA 以来**最大的一次版本升级**。它不是一次简单的 API 修补，而是从底层架构到编程范式的一次**全面重构**。

| 维度 | 1.x 时代 | 2.0 时代 |
|---|---|---|
| **主入口** | `ChatModel.call()` 偏底层 | `ChatClient` Fluent API 为核心入口 |
| **架构风格** | 单体 `spring-ai-core` 包 | 领域驱动模块化（5大核心模块） |
| **Java基线** | Java 17 | Java 21（强制） |
| **工具调用** | 各 ChatModel 内置执行逻辑 | 统一 ToolCallingManager 集中管理 |
| **MCP集成** | 社区适配 | MCP SDK 2.0 原生深度集成 |
| **SDK封装** | 自行封装HTTP | OpenAI / Anthropic 原生SDK |

### 1.2 Maven 坐标（正式GA版）

```xml
<!-- BOM 统一版本管理 -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>2.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

---

## 二、架构之变：从单体到模块化（最核心变化）

### 2.1 为什么重构？

1.x 时代，一个日益膨胀的 `spring-ai-core` 包承载了从大语言模型到嵌入模型、从图像生成到向量存储的**全部能力**，已膨胀至难以维护。2.0 彻底摒弃单体模式，采用**领域驱动模块化（Domain-Driven Modularization）**策略。

### 2.2 五大核心领域模块

```
spring-ai-commons        ← 基础层（零外部依赖）
    ↑
spring-ai-model          ← 模型抽象层（ChatModel/EmbeddingModel/ImageModel）
    ↑
spring-ai-client-chat    ← 客户端抽象层（ChatClient Fluent API）
    ↑
spring-ai-vector-store   ← 存储抽象层（VectorStore）
    ↑
spring-ai-advisors-vector-store  ← 桥接模块（Advisor连接VectorStore与ChatClient）
```

#### 各模块职责详解：

| 模块 | 职责 | 关键类 |
|---|---|---|
| `spring-ai-commons` | 定义 Message、Prompt、Media 等通用领域模型 | `Message`, `Prompt`, `Media`, `MimeType` |
| `spring-ai-model` | 所有 AI 模型能力的统一接口（Chat/Embedding/Image/Moderation） | `ChatModel`, `EmbeddingModel`, `ToolCallback`, `ToolDefinition` |
| `spring-ai-client-chat` | ChatClient Fluent API + Advisor 链 | `ChatClient`, `Advisor`, `CallResponseSpec` |
| `spring-ai-vector-store` | 向量存储抽象 | `VectorStore`, `Document`, `SearchRequest` |
| `spring-ai-advisors-vector-store` | 将 VectorStore 通过 Advisor 桥接到 ChatClient | `QuestionAnswerAdvisor` |

### 2.3 模型目录重组

所有模型客户端统一移到 `models/` 目录，清理了废弃集成（Vertex AI、ZhiPu AI、OCI GenAI 标记废弃并移除），支持 **50+ 模型**。

### 2.4 Starter 命名规范变更

```xml
<!-- 2.0 新的 Starter 命名格式 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

> **命名模式**：`spring-ai-starter-{capability}-{provider}`

---

## 三、核心特性全解析

### 3.1 工具调用（Tool Calling）—— 彻底重构 ⭐⭐⭐

这是 2.0 最核心的架构变化之一。

#### 1.x 的问题

每个 `ChatModel` 内部都内置了一套工具执行逻辑（普通调用、流式调用、工具执行循环控制），模型和工具**紧耦合**。

#### 2.0 的解决方案

把工具执行循环从各个 ChatModel 中**抽离**出来，由 `ToolCallingManager` **统一处理**。

```
┌──────────────────────────────────────────────────┐
│                   ChatClient                      │
│    ┌─────────────┐   ┌──────────────────────┐   │
│    │  ChatModel   │   │ ToolCallingManager   │   │
│    │ (只负责推理)  │   │ (统一执行工具循环)     │   │
│    └─────────────┘   └──────────────────────┘   │
│                              │                    │
│              ┌───────────────┼───────────────┐   │
│              ▼               ▼               ▼   │
│       MethodTool     FunctionTool      MCP Tool  │
└──────────────────────────────────────────────────┘
```

#### 核心代码结构

```
spring-ai-model/
├── tool/                          # 工具调用核心
│   ├── ToolCallback.java          # 工具回调接口
│   ├── ToolDefinition.java        # 工具定义（名称、描述、参数Schema）
│   ├── method/
│   │   └── MethodToolCallback.java    # 基于方法的工具
│   ├── function/
│   │   └── FunctionToolCallback.java  # 基于函数的工具
│   └── resolution/               # 工具解析器
```

#### 实战代码示例

**方案一：方法级 @Tool 注解（推荐入门）**

```java
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

@Service
public class WeatherService {

    @Tool(description = "查询指定城市的实时天气信息")
    public WeatherInfo getWeather(String city) {
        // 调用天气API
        return weatherApiClient.query(city);
    }

    @Tool(description = "根据订单号查询订单状态")
    public OrderStatus queryOrder(String orderId) {
        return orderRepository.findById(orderId).getStatus();
    }
}
```

**Controller 层使用：**

```java
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final ChatClient chatClient;

    public AiController(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultSystem("你是一个智能助手，可以查询天气和订单信息。")
                .defaultTools(new WeatherService(), new OrderService())
                .build();
    }

    @PostMapping("/chat")
    public String chat(@RequestBody String userMessage) {
        return chatClient.prompt()
                .user(userMessage)
                .call()
                .content();
    }

    // 流式响应
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                .stream()
                .content();
    }
}
```

**方案二：MCP 协议工具调用（企业级）**

```yaml
# application.yml
spring:
  ai:
    mcp:
      client:
        enabled: true
        name: spring-ai-agent
        type: async
        sse:
          connections:
            12306-mcp:
              url: https://mcp.example.com/sse
            weather-mcp:
              url: https://weather-mcp.example.com/sse
```

---

### 3.2 MCP（Model Context Protocol）2.0 原生集成 ⭐⭐⭐

#### 什么是 MCP？

MCP 是由 Anthropic 提出的开放标准协议，让大模型以**统一标准**与外部工具和数据交互。Spring AI 2.0 集成了 **MCP SDK 2.0**。

#### 2.0 的 MCP 增强

| 特性 | 说明 |
|---|---|
| 原生SDK集成 | MCP SDK 2.0 深度集成，非社区适配 |
| SSE/Stdio 双模式 | 支持 Server-Sent Events 和标准输入输出 |
| 安全认证 | API Key 认证，由 mcp-security 社区项目支持 |
| 动态工具发现 | 运行时动态注册/注销 MCP 工具 |

#### MCP Client 配置

```java
@Configuration
public class McpConfig {

    @Bean
    public McpClient mcpClient() {
        return McpClient.builder()
                .sseConnection("my-server", "https://mcp-server.example.com/sse")
                .build();
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, McpClient mcpClient) {
        return builder
                .defaultTools(mcpClient.getToolCallbacks())  // 自动注册MCP工具
                .build();
    }
}
```

---

### 3.3 ChatClient —— 统一入口 Fluent API ⭐⭐⭐

2.0 将 `ChatClient` 确立为**与 LLM 交互的核心入口**，取代直接使用 `ChatModel.call()`。

#### 核心设计理念

ChatClient 将 Prompt 模板、聊天记忆、LLM、输出解析器、RAG 组件等**统一编排**，类似 `WebClient` 之于 HTTP 客户端。

#### 完整示例：系统提示 + 记忆 + 工具 + RAG + 结构化输出

```java
@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(
            ChatClient.Builder builder,
            ChatMemory chatMemory,
            VectorStore vectorStore) {

        return builder
                // 1. 系统提示词
                .defaultSystem("你是企业知识库助手，请用中文回答。")
                // 2. 聊天记忆
                .defaultAdvisors(
                    new MessageChatMemoryAdvisor(chatMemory),
                    new QuestionAnswerAdvisor(vectorStore)  // RAG
                )
                // 3. 默认工具
                .defaultTools(new InternalApiTools())
                .build();
    }
}
```

```java
@Service
public class KnowledgeAssistant {

    private final ChatClient chatClient;

    public KnowledgeAssistant(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    // 基础对话
    public String ask(String question, String conversationId) {
        return chatClient.prompt()
                .user(question)
                .advisors(spec -> spec.param("chat_memory_conversation_id", conversationId))
                .call()
                .content();
    }

    // 结构化输出（自动映射为 Java 对象）
    public ProductSummary analyzeProduct(String query) {
        return chatClient.prompt()
                .user(query)
                .call()
                .entity(ProductSummary.class);  // 自动JSON反序列化
    }

    // 流式 + 结构化
    public Flux<String> streamAsk(String question) {
        return chatClient.prompt()
                .user(question)
                .stream()
                .content();
    }
}
```

---

### 3.4 Advisor 机制 —— 面向切面的AI编程 ⭐⭐⭐

Advisor 是 Spring AI 2.0 最优雅的设计之一，类似 Spring AOP 的拦截器概念，用于在 ChatClient 调用前后插入逻辑。

#### Advisor 执行链

```
用户输入 → [PreAdvisor1] → [PreAdvisor2] → LLM调用 → [PostAdvisor1] → [PostAdvisor2] → 最终响应
```

#### 内置 Advisor

| Advisor | 用途 |
|---|---|
| `MessageChatMemoryAdvisor` | 自动管理多轮对话记忆 |
| `QuestionAnswerAdvisor` | RAG 检索增强（自动从向量库检索相关文档注入上下文） |
| `SafeGuardAdvisor` | 安全护栏（过滤敏感内容） |
| `SimpleLoggerAdvisor` | 日志记录 |

#### 自定义 Advisor 示例

```java
public class AuditLogAdvisor implements CallAroundAdvisor {

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        // 前置：记录请求
        log.info("AI请求: {}", request.userText());

        AdvisedResponse response = chain.nextAroundCall(request);

        // 后置：记录响应
        log.info("AI响应: {}", response.response().getResult().getOutput().getText());
        auditRepository.save(new AuditLog(request, response));

        return response;
    }

    @Override
    public int getOrder() { return 0; }  // 执行顺序
}
```

---

### 3.5 RAG（检索增强生成）—— 模块化重构 ⭐⭐

#### 1.x → 2.0 的 RAG 变化

1.x 的 RAG 是一个黑盒式 Pipeline，2.0 将其拆解为**可编排的模块化架构**：

```
Pre-Retrieval → Retrieval → Post-Retrieval → Generation
   (查询改写)    (向量检索)    (结果重排)      (生成回答)
```

#### 完整 RAG 实战

```java
@Configuration
public class RagConfig {

    // 1. 嵌入模型
    @Bean
    public EmbeddingModel embeddingModel() {
        return new OpenAiEmbeddingModel(
            new OpenAiEmbeddingApi(System.getenv("OPENAI_API_KEY"))
        );
    }

    // 2. 向量存储
    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return new RedisVectorStore(
            RedisVectorStore.RedisVectorStoreConfig.builder()
                .withIndexName("knowledge-base")
                .build(),
            embeddingModel
        );
    }

    // 3. 文档加载与切分
    @Bean
    public CommandLineRunner ingestDocuments(VectorStore vectorStore) {
        return args -> {
            // 从多种来源加载文档
            var pdfReader = new TikaDocumentReader(new ClassPathResource("docs/manual.pdf"));
            var splitter = new TokenTextSplitter(800, 200, 5, 10000, true);
            List<Document> docs = splitter.apply(pdfReader.get());
            vectorStore.add(docs);
        };
    }
}
```

```java
@Service
public class RagService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    // 使用 QuestionAnswerAdvisor 实现 RAG
    public String askWithContext(String question) {
        return chatClient.prompt()
                .user(question)
                .advisors(new QuestionAnswerAdvisor(vectorStore,
                    SearchRequest.builder()
                        .topK(5)
                        .similarityThreshold(0.7)
                        .build()))
                .call()
                .content();
    }
}
```

---

### 3.6 结构化输出（Structured Output） ⭐⭐

让 LLM 输出直接映射为 Java POJO，无需手动解析 JSON。

```java
// 定义目标类
public record ProductSummary(
    String name,
    double price,
    List<String> features,
    Rating rating
) {}

public record Rating(double score, int reviewCount) {}

// 使用
ProductSummary summary = chatClient.prompt()
        .user("分析以下产品信息并提取关键数据：...")
        .call()
        .entity(ProductSummary.class);  // 自动转换！
```

```java
// 列表输出
List<Invoice> invoices = chatClient.prompt()
        .user("从以下文本中提取所有发票信息")
        .call()
        .entity(new ParameterizedTypeReference<List<Invoice>>() {});
```

---

### 3.7 多模型无缝切换

2.0 支持通过 Profile 或配置实现模型热切换，**业务代码零改动**：

```yaml
# application.yml - 使用 OpenAI
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o
          temperature: 0.7
```

```yaml
# application-anthropic.yml - 切换为 Anthropic Claude
spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      chat:
        options:
          model: claude-sonnet-4-20250514
```

```yaml
# application-ollama.yml - 本地 Ollama
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        model: llama3:70b
```

**业务代码完全不变：**

```java
// 无论后端是 GPT/Claude/Llama，这段代码都不用改
String answer = chatClient.prompt().user(question).call().content();
```

---

### 3.8 Anthropic SDK 全线重构 ⭐⭐

2.0 对 Anthropic 集成进行了彻底重构，从自行封装 HTTP 切换到**原生 Anthropic Java SDK**：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-anthropic</artifactId>
</dependency>
```

支持的 Claude 特性：
- ✅ 扩展思考（Extended Thinking）
- ✅ 工具调用
- ✅ 视觉（多模态）
- ✅ 流式响应
- ✅ Prompt 缓存

---

### 3.9 OpenAI 原生 SDK 集成

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

2.0 采用 OpenAI 官方 Java SDK 作为底层，而非自行封装 REST 调用。

---

### 3.10 配置属性简化

2.0 **移除了配置文件中多余的 `.options` 层级**：

```yaml
# 1.x 写法（旧）
spring:
  ai:
    openai:
      chat:
        options:
          model: gpt-4o
          temperature: 0.7

# 2.0 写法（新，更简洁）
spring:
  ai:
    openai:
      chat:
        model: gpt-4o
        temperature: 0.7
```

---

### 3.11 可观测性（Observability）

2.0 内建了与 Spring Boot Actuator + Micrometer + OpenTelemetry 的深度集成：

```yaml
management:
  observations:
    include-context: true
  tracing:
    sampling:
      probability: 1.0
  metrics:
    tags:
      application: ${spring.application.name}
```

可观测的内容包括：
- LLM 调用延迟、Token 使用量
- 工具调用耗时与成功率
- RAG 检索命中率
- Advisor 链执行链路追踪

---

## 四、Agent 智能体开发 ⭐⭐⭐

Spring AI 2.0 是面向 **Agent 开发**设计的框架，不只是简单的"调用大模型API"。

### 4.1 Agent = LLM + 记忆 + 工具 + RAG + 规划

```java
@Bean
public ChatClient agentClient(ChatClient.Builder builder,
                               ChatMemory memory,
                               VectorStore vectorStore,
                               ToolCallbackProvider toolProvider) {
    return builder
            .defaultSystem("""
                你是一个高级AI助手，具备以下能力：
                1. 查询企业知识库
                2. 调用内部API（订单、库存、用户）
                3. 记忆历史对话
                请根据用户需求，合理调用工具完成任务。
                """)
            .defaultAdvisors(
                new MessageChatMemoryAdvisor(memory),
                new QuestionAnswerAdvisor(vectorStore)
            )
            .defaultToolCallbacks(toolProvider.getToolCallbacks())
            .build();
}
```

### 4.2 多轮工具调用（Agent Loop）

2.0 的 `ToolCallingManager` 支持**递归工具调用**：

```
用户: "帮我查一下订单 #12345 的物流状态，如果已发货就告诉我预计到达时间"

LLM推理 → 调用 queryOrder("12345") → 获取订单状态
LLM推理 → 调用 trackLogistics("12345") → 获取物流信息
LLM推理 → 生成最终回复
```

这个循环由框架**自动管理**，开发者只需注册工具即可。

---

## 五、完整项目实战：从零搭建企业级AI应用

### 5.1 pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.0</version>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>spring-ai-demo</artifactId>
    <version>1.0.0</version>

    <properties>
        <java.version>21</java.version>
        <spring-ai.version>2.0.0</spring-ai.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- Spring Boot Web -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Spring AI OpenAI Starter -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-openai</artifactId>
        </dependency>

        <!-- Spring AI Redis Vector Store -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-redis-store</artifactId>
        </dependency>

        <!-- Spring AI MCP Client -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-mcp-client</artifactId>
        </dependency>

        <!-- 文档解析 -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-tika-document-reader</artifactId>
        </dependency>
    </dependencies>
</project>
```

### 5.2 application.yml

```yaml
spring:
  application:
    name: enterprise-ai-agent
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        model: gpt-4o
        temperature: 0.7
      embedding:
        model: text-embedding-3-small
    mcp:
      client:
        enabled: true
        sse:
          connections:
            knowledge-mcp:
              url: ${MCP_SERVER_URL}

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,metrics,info
```

### 5.3 主应用类

```java
@SpringBootApplication
public class EnterpriseAiAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(EnterpriseAiAgentApplication.class, args);
    }
}
```

### 5.4 配置类

```java
@Configuration
public class AgentConfig {

    @Bean
    public ChatMemory chatMemory() {
        return new InMemoryChatMemory();  // 生产环境可换 RedisChatMemory
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                  ChatMemory chatMemory,
                                  VectorStore vectorStore,
                                  WeatherService weatherService,
                                  OrderService orderService) {
        return builder
                .defaultSystem("你是企业智能助手，请用中文简洁回答。")
                .defaultAdvisors(
                    new MessageChatMemoryAdvisor(chatMemory),
                    new QuestionAnswerAdvisor(vectorStore)
                )
                .defaultTools(weatherService, orderService)
                .build();
    }
}
```

### 5.5 工具类

```java
@Service
public class WeatherService {
    @Tool(description = "查询指定城市的天气")
    public String getWeather(String city) {
        return String.format("%s: 晴, 28°C", city);
    }
}

@Service
public class OrderService {
    @Tool(description = "查询订单详情，参数为订单号")
    public OrderInfo getOrderInfo(String orderId) {
        return new OrderInfo(orderId, "已发货", "预计明天到达");
    }
    public record OrderInfo(String orderId, String status, String delivery) {}
}
```

### 5.6 REST API

```java
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final ChatClient chatClient;

    public AgentController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @PostMapping("/chat")
    public ResponseEntity<String> chat(
            @RequestParam String conversationId,
            @RequestBody String message) {

        String response = chatClient.prompt()
                .user(message)
                .advisors(spec -> spec
                    .param("chat_memory_conversation_id", conversationId))
                .call()
                .content();

        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(
            @RequestParam String conversationId,
            @RequestParam String message) {

        return chatClient.prompt()
                .user(message)
                .advisors(spec -> spec
                    .param("chat_memory_conversation_id", conversationId))
                .stream()
                .content()
                .map(content -> ServerSentEvent.<String>builder()
                        .data(content)
                        .build());
    }

    @PostMapping("/analyze")
    public ResponseEntity<AnalysisResult> analyze(@RequestBody String text) {
        AnalysisResult result = chatClient.prompt()
                .user("请分析以下文本并提取结构化信息：" + text)
                .call()
                .entity(AnalysisResult.class);
        return ResponseEntity.ok(result);
    }
}
```

---

## 六、从 1.x 迁移到 2.0 的关键 Checklist

| # | 迁移项 | 操作 |
|---|---|---|
| 1 | **Java 版本** | 必须升级到 Java 21 |
| 2 | **Spring Boot** | 升级到 4.0+ |
| 3 | **Starter 命名** | `spring-ai-openai-spring-boot-starter` → `spring-ai-starter-model-openai` |
| 4 | **配置属性** | 移除 `.options` 层级 |
| 5 | **Tool Calling** | `@Function` → `@Tool`，`FunctionCallback` → `ToolCallback` |
| 6 | **ChatClient** | 优先使用 `ChatClient` 替代直接 `ChatModel.call()` |
| 7 | **RAG** | 旧的 `RetrievalAugmentationAdvisor` → 模块化 Pre/Post Retrieval |
| 8 | **MCP** | 升级到 MCP SDK 2.0 新配置格式 |
| 9 | **包路径** | 检查 import，核心类路径有变化 |
| 10 | **废弃模型** | Vertex AI、ZhiPu AI 等已移除 |

---

## 七、核心概念速查表

```
┌──────────────────── Spring AI 2.0 核心概念 ────────────────────┐
│                                                                 │
│  ChatModel ─── 模型抽象（GPT/Claude/Gemini/Llama...）           │
│      │                                                          │
│  ChatClient ── 统一入口（Fluent API，90%场景用这个）             │
│      │                                                          │
│  Advisor ───── 拦截器链（记忆/RAG/安全护栏/日志...）             │
│      │                                                          │
│  ToolCallback ─ 工具注册（@Tool注解 / Method / Function / MCP）  │
│      │                                                          │
│  VectorStore ── 向量存储（Redis/Pinecone/Chroma/PgVector...）   │
│      │                                                          │
│  EmbeddingModel 嵌入模型（文本→向量）                            │
│      │                                                          │
│  DocumentReader 文档读取（PDF/Word/HTML/Markdown...）            │
│      │                                                          │
│  MCP Client ─── 模型上下文协议客户端（标准化工具协议）            │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 八、总结与落地建议

### 适合采用 Spring AI 2.0 的场景

1. ✅ 已有 Spring Boot 技术栈的企业，需要集成 AI 能力
2. ✅ 需要多模型切换（避免供应商锁定）
3. ✅ 构建企业级 Agent / RAG 知识库
4. ✅ 对可观测性、安全性有生产级要求
5. ✅ 需要 MCP 协议标准化工具集成

### 落地路径建议

```
第一步：简单对话集成（ChatClient + OpenAI/Ollama）
  ↓
第二步：加入工具调用（@Tool + 内部API）
  ↓
第三步：加入 RAG（VectorStore + QuestionAnswerAdvisor）
  ↓
第四步：加入记忆（ChatMemory + MessageChatMemoryAdvisor）
  ↓
第五步：MCP 生态集成（连接外部 MCP 服务）
  ↓
第六步：可观测性 + 安全护栏（生产上线）
```

Spring AI 2.0 的核心哲学是：**AI 能力不再是"附加功能"，而是像 Spring Data、Spring Security 一样的"原生基础设施"**。它让 Java 开发者可以用最熟悉的 Spring 模式（依赖注入、自动配置、AOP）来构建 AI 应用，真正实现了"Java 在 AI 开发领域与 Python 的差距大幅缩小"这一愿景。



