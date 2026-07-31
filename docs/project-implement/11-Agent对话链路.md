# 第十一章：Agent 对话链路

> 本章为《企业知识库 RAG Agent 工作台：Spring AI 2.0 全景实现报告》v2 拆分版的一部分（原第五卷「核心模块技术实现」）
>
> [📑 返回目录](./README.md) · 最后更新：2026-07-31
>
> **v2 修订**：① 11.1 核心 Advisor 由手搓 `PrefetchRagAdvisor` 改为第十章的 `RetrievalAugmentationAdvisor` 组装 + 瘦 `RetrievalTraceAdvisor`；② 全部虚构 API 修正为 2.0.0 GA 真实 API（`ChatClientRequest.from()`、`ToolContext.requestApproval()`、`RedisChatMemory`、`ToolRegistry.merge()` 等，详见各节 v2 注）；③ MCP 传输 SSE → Streamable HTTP。

---

## 11.1 检索增强与溯源透传

RAG 检索与证据注入的完整实现见**第十章**（`RetrievalAugmentationAdvisor` + `HybridDocumentRetriever` + `RrfFusion` + `RerankDocumentPostProcessor` + `ContextualQueryAugmenter`）。本章只定义对话链路上的**溯源透传层**：把检索过程的 trace 数据送到 SSE TRACE 事件与审计日志。

### 11.1.1 RetrievalTraceAdvisor（瘦 Advisor，v2 新增）

职责有二：① 在 Advisor 链入口填充请求级 `RetrievalContext`（tenant_id、ACL，供检索过滤器使用，见 10.2.1）；② 在 LLM 调用后将检索 trace 写入响应上下文，供 Controller 层推送 SSE TRACE 事件与审计落库。

```java
package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.api.security.JwtUtils;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 检索溯源 Advisor —— Order 450（先于 RetrievalAugmentationAdvisor 的 500）
 *
 * <p>v2 注：Spring AI 2.0 的 ChatClientRequest 是不可变 record，
 * 无 v1 文档中的 from() 静态构造器；需要变换请求时经 Prompt 重建。</p>
 */
@Component
public class RetrievalTraceAdvisor implements BaseAdvisor {

    private final ObjectProvider<RetrievalContext> retrievalContextProvider;
    private final JwtUtils jwtUtils;

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        // 1. 从 SecurityContext 提取 JWT claims 填充请求级检索上下文
        RetrievalContext ctx = retrievalContextProvider.getObject();
        ctx.setTenantId(jwtUtils.getCurrentTenantId());   // owner claim
        ctx.setUserId(jwtUtils.getCurrentUserId());       // sub claim

        // 2. 透传初始 trace 信息到 advisor 上下文
        Map<String, Object> context = new HashMap<>(request.context());
        context.put("trace_start_ms", System.currentTimeMillis());
        // record 重建：new ChatClientRequest(prompt, context)，无 from()
        return new ChatClientRequest(request.prompt(), context);
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        RetrievalContext ctx = retrievalContextProvider.getObject();
        // 检索 trace（双路原始命中 + 融合排名 + 重排分）附加到响应上下文
        return ChatClientResponse.builder()
            .chatResponse(response.chatResponse())        // v1 误写为 response.response()
            .context(Map.of(
                "rag_trace", ctx.getTraceSummary(),       // List<TraceEntry>：供 SSE TRACE 事件
                "retrieval_count", ctx.getTraceSummary().size(),
                "top_score", ctx.getTopFusionScore()))
            .build();
    }

    @Override
    public int getOrder() { return 450; }
}
```

> **v1 → v2 API 修正**：
> - `ChatClientRequest.from(request).systemText(...).context(...).build()` → **不存在**。`ChatClientRequest` 是 record，仅有 `new ChatClientRequest(Prompt, Map)` 构造器与 `prompt()`/`context()` 访问器；Prompt 级变换用 `Prompt.builder()` / `Prompt.mutate()`。
> - `response.response()` → 实际访问器为 **`response.chatResponse()`**。
> - 证据注入（Grounding Prompt 组装）不再手写：由 `ContextualQueryAugmenter` 承担（第十章 10.6）。

### 11.1.2 Grounding 与 [ref-N] 标注

回答中的 `[ref-N]` 标注约定写入 `ContextualQueryAugmenter` 的 prompt 模板（第十章 10.6 `GROUNDING_PROMPT`）；N 与 SSE TRACE 事件中 `rag_trace` 列表的下标一一对应，前端溯源卡片按此映射渲染。v1 设想的独立 `GroundingAdvisor` 后处理不再需要——标注由模型在 Grounding 约束下直接生成，溯源数据由 trace 层旁路透传，避免对模型输出做脆弱的文本后处理。

---

## 11.2 企业级 ChatClient 装配

### Advisor 链（v2 修订）

> **v2 Order 重排说明**：v1 顺序中 Auth(200) 位于 RateLimit(100) 之后，导致未鉴权请求可消耗租户限流配额（匿名洪泛 → 合法用户 DoS），且租户级限流/预算拿不到 tenant_id。v2 重排为「先审计 → 再鉴权 → 后限流/预算」；审计保持最外层以记录被拒/被限流的攻击请求。

| Order | Advisor | 接口 | 职责 | 阶段 |
|-------|---------|------|------|------|
| **10** | `AuditTraceAdvisor` | `BaseAdvisor` (before+after) | 全链路审计日志落库（最外层，含被拒/被限流请求；trace 数据源即 RetrievalContext） | P2 |
| **20** | `AuthAdvisor` | `BaseAdvisor` (before) | 对话级鉴权，提取用户/租户入上下文（HTTP 层 SecurityConfig 已做粗粒度） | P2 |
| **30** | `TokenBudgetAdvisor` | `BaseAdvisor` (before+after) | Token 消耗统计、成本追踪、预算告警（依赖已鉴权 tenant_id） | P2 |
| **100** | `RateLimitAdvisor` | `BaseAdvisor` (before) | Redisson 令牌桶限流（远期可迁 Higress AI Gateway，见第十二章注） | P2 |
| **110** | `OutputGuardrailAdvisor` | `BaseAdvisor` (after) | 输出合规审查、竞品过滤（升级路线见 12.2.1） | P2 |
| **300** | `InputSanitizeAdvisor` | `BaseAdvisor` (before) | PII 脱敏、注入检测（升级路线见 12.1.1；先于记忆避免 PII 落库） | P2 |
| **400** | `MessageChatMemoryAdvisor` | `BaseAdvisor` | 多轮对话记忆（Redis 仓储） | P2 |
| **450** | `RetrievalTraceAdvisor` ★ | `BaseAdvisor` (before+after) | 检索上下文填充 + 溯源透传 | **P1** |
| **500** | `RetrievalAugmentationAdvisor` ★ | `BaseAdvisor` | **核心**：查询改写 + 双路检索 + RRF + 重排 + 证据注入（第十章） | **P1** |
| **1000** | `ToolCallingAdvisor` | `CallAdvisor` | 工具调用循环（@Tool + MCP） | P2 |

```java
package com.enterprise.kb.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.redis.RedisChatMemoryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

@Configuration
public class AgentChatClientConfig {

    /**
     * Redis 会话记忆（v2 修正）
     *
     * <p>v1 文档中的 RedisChatMemory 类不存在。Spring AI 2.0 的真实形态：
     * Redis 实现位于仓储层（RedisChatMemoryRepository），
     * 再包装进标准的消息窗口 ChatMemory。</p>
     */
    @Bean
    public ChatMemory redisChatMemory(RedisTemplate<String, Object> redisTemplate) {
        return MessageWindowChatMemory.builder()
            .chatMemoryRepository(new RedisChatMemoryRepository(redisTemplate))
            .build();
    }

    @Bean
    public ChatClient knowledgeAgentChatClient(
            ChatClient.Builder builder,
            ChatMemory redisChatMemory,
            RetrievalAugmentationAdvisor retrievalAugmentationAdvisor,
            RetrievalTraceAdvisor retrievalTraceAdvisor
            /* Phase 3 追加：tokenBudgetAdvisor, auditTraceAdvisor, rateLimitAdvisor,
               outputGuardrailAdvisor, authAdvisor, inputSanitizeAdvisor, toolCallingAdvisor */) {

        return builder
            .defaultSystem("你是企业知识库 RAG Agent 助手。")
            .defaultAdvisors(
                // Phase 1 形态仅含检索两位（替代 QuestionAnswerAdvisor）
                retrievalTraceAdvisor,
                retrievalAugmentationAdvisor,
                MessageChatMemoryAdvisor.builder(redisChatMemory).build()
            )
            .build();
    }
}
```

> **v2 注（会话 ID）**：多轮对话的会话标识通过 advisor 参数传递，键为 `ChatMemory.CONVERSATION_ID`（值 `"chat_memory_conversation_id"`，经核验 2.0 未变）：`.advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))`。`RedisChatMemoryRepository`/`MessageWindowChatMemory` 的构造器形态（builder 参数名）在实现时以 Javadoc 为准。

### 11.2.1 @Tool 工具调用与 Human-in-the-Loop（v2 重写）

> **v1 重大错误修正**：`ToolContext.isApproved()` / `ToolContext.requestApproval(String)` **完全虚构**。`ToolContext` 仅有 `getContext()`（不可变 Map）。Spring AI 2.0 没有内建的工具审批 API，HITL 需按下列真实机制组合实现。

```java
package com.enterprise.kb.ai.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class EnterpriseTools {

    /** 读操作 —— 自动执行，无审批需求 */
    @Tool(description = "根据员工姓名或工号查询员工基本信息，包括部门、职位、入职日期")
    public EmployeeInfo queryEmployee(
            @ToolParam(description = "员工姓名或工号") String keyword) {
        return erpService.findEmployee(keyword);
    }

    /**
     * 写操作 —— HITL 三段式（v2 真实机制）：
     * 1. 工具自身做「预检 + 挂起」：首次调用不落库，返回待确认摘要 + approvalId
     * 2. 前端经 SSE TOOL_CALL 事件（status=PENDING_APPROVAL）弹出确认卡片
     * 3. 用户确认后，前端携带 approvalId 发起二次对话，工具校验通过才执行
     */
    @Tool(description = "提交员工请假申请。首次调用返回待确认摘要，用户确认后才会真正提交")
    public String submitLeaveRequest(
            @ToolParam(description = "员工工号") String employeeId,
            @ToolParam(description = "请假开始日期 (yyyy-MM-dd)") String startDate,
            @ToolParam(description = "请假结束日期 (yyyy-MM-dd)") String endDate,
            @ToolParam(description = "请假类型: 年假/事假/病假") String leaveType,
            ToolContext context) {

        // 通过 advisor 参数 → ToolContext 上下文传递确认态（无 isApproved() 方法）
        Object approval = context.getContext().get("approval:" + employeeId + ":" + leaveType);
        if (approval == null) {
            String approvalId = approvalService.createPending(employeeId, startDate, endDate, leaveType);
            return "⏳ PENDING_APPROVAL:" + approvalId
                + " 请确认：为员工 " + employeeId + " 提交 " + leaveType
                + " 请假（" + startDate + " 至 " + endDate + "）";
        }
        approvalService.verifyAndConsume(approval.toString());
        return oaService.submitLeave(employeeId, startDate, endDate, leaveType);
    }
}
```

**可选的框架级增强**：自定义 `ToolExecutionEligibilityChecker` 在工具执行前统一拦截写操作工具（按工具名前缀 `write_` 约定），以及 `ToolMetadata.returnDirect()` 让特定工具结果直达用户不经模型润色。`ToolCallingAdvisor` 装配时设置最大调用次数防循环：

```java
ToolCallingAdvisor.builder()
    /* toolCallbacks / toolCallbackProviders 经 ChatClient.defaultTools(...) 注入 */
    .build();
```

### 11.2.2 SmartRoutingChatModel 多模型智能路由

（v1 设计保持不变——仅依赖 `ChatModel`/`ChatResponse`/`Prompt` 真实接口，无虚构 API。要点复述：按查询复杂度分级 ECONOMY/STANDARD/PREMIUM，轮询 + 熔断器保护（连续失败 5 次熔断 30 秒），层级降级 PREMIUM→STANDARD→ECONOMY。Phase 3.2 实现。）

### 11.2.3 MCP 工具集成（v2 修订）

> **v2 修正**：① SSE 传输自 Spring AI 2.0.0 起**已废弃**，新接入使用 **Streamable HTTP**；② `ToolRegistry.merge(...)` 不存在——合并多来源工具经 `ChatClient.Builder.defaultTools(provider1, provider2, ...)`。

```yaml
# application.yml - MCP Client 配置（Spring AI 2.0.0 GA）
spring:
  ai:
    mcp:
      client:
        # Streamable HTTP（推荐；sse.* 前缀仍可用但已废弃）
        streamable-http:
          connections:
            erp-server:
              url: http://erp-mcp.internal:8080/mcp
            oa-server:
              url: http://oa-mcp.internal:8080/mcp
        stdio:
          connections:
            local-tools:
              command: java
              args: ["-jar", "/opt/mcp/local-tools-server.jar"]
```

（streamable-http 配置前缀以 2.0 GA 自动配置类为准，实现时核验。）本地 @Tool 工具与 MCP 引入工具统一经 `defaultTools(...)` 合并注入，工具数量膨胀后换 `ToolSearchToolCallingAdvisor` 按需检索（11.2.6）。

### 11.2.4 结构化输出 `.entity()`

（v1 设计保持不变——`CallResponseSpec.entity(Class)` / `entity(ParameterizedTypeReference)` 经核验真实存在。`AnswerWithCitations` record + Bean Validation 约束的用法正确。）

### 11.2.5 StructuredOutputValidationAdvisor 自校正

（v1 设计保持不变——经源码核验真实存在：`StructuredOutputValidationAdvisor.builder().outputType(...).maxRepeatAttempts(3)`。注意不支持流式调用。）

### 11.2.6 ToolSearchToolCallingAdvisor 按需工具检索

（v1 设计保持不变——`ToolSearchToolCallingAdvisor.builder().toolIndex(LuceneToolIndex...).maxResults(5)` 经核验真实存在。v1 中 `ToolRegistry.merge` 引用已删除：工具注册经 `ChatClient.Builder.defaultTools(Object...)` 接受多个 `ToolCallbackProvider`。）

---

## 11.3 SSE 流式事件推送（v2 修订）

事件类型设计保持不变（TOKEN / TRACE / TOOL_CALL / ERROR / HEARTBEAT / DONE），修正数据获取路径：

```java
package com.enterprise.kb.api.controller;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentStreamController {

    private final ChatClient knowledgeAgent;

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentStreamEvent>> streamChat(
            @RequestBody ChatRequest request,
            @RequestParam String sessionId) {

        // 请求级 trace 捕获器（请求作用域 Bean，RetrievalTraceAdvisor 填充）
        RetrievalContext trace = retrievalContextProvider.getObject();

        return knowledgeAgent.prompt()
            .user(request.query())
            .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
            .stream()
            .chatResponse()
            .flatMap(response -> {
                // TOKEN 事件：v2 修正访问路径 chatResponse()（原误用 response()）
                if (response.getResult() != null) {
                    String text = response.getResult().getOutput().getText();
                    if (text != null && !text.isEmpty()) {
                        return Mono.just(ServerSentEvent.<AgentStreamEvent>builder()
                            .event("TOKEN").data(new TokenEvent(text)).build());
                    }
                }
                return Mono.empty();   // v1 的空 HEARTBEAT 刷屏改为静默
            })
            .concatWith(Mono.fromSupplier(() -> {
                // TRACE 事件：流结束时从 RetrievalContext 取完整溯源（10.1 元数据约定）
                return ServerSentEvent.<AgentStreamEvent>builder()
                    .event("TRACE")
                    .data(new TraceEvent(trace.getTraceSummary())).build();
            }))
            .concatWith(Mono.just(ServerSentEvent.<AgentStreamEvent>builder()
                .event("DONE").data(new DoneEvent(sessionId)).build()))
            .onErrorResume(e -> Flux.just(ServerSentEvent.<AgentStreamEvent>builder()
                .event("ERROR")
                .data(new ErrorEvent("CHAT_FAILED", e.getMessage())).build()));
    }
}

// 事件类型定义（v1 保持不变）
sealed interface AgentStreamEvent permits TokenEvent, TraceEvent, ToolCallEvent,
        ErrorEvent, DoneEvent {}
record TokenEvent(String token) implements AgentStreamEvent {}
record TraceEvent(List<TraceEntry> sources) implements AgentStreamEvent {}
record ToolCallEvent(String toolName, String status, String result) implements AgentStreamEvent {}
record ErrorEvent(String code, String message) implements AgentStreamEvent {}
record DoneEvent(String sessionId) implements AgentStreamEvent {}
```

> **v2 注（流式上下文传递）**：Reactor 流式链路跨线程，Advisor 上下文（`ChatClientResponse.context()`）在流末不易取回，故溯源数据改由请求作用域 `RetrievalContext` 旁路传递——与检索过滤器共用同一机制（10.2.1），一处设计两处复用。前端对 SSE 的消费协议与 Phase 1 兼容（`data: {"token":"..."}` 追加，新增 `event: TRACE` 命名事件，旧前端忽略即可）。

---

## 11.4 自适应路由预留（Phase 5.4 设计锚点）

11.2.2 的 `SmartRoutingChatModel` 是**模型层**路由（按复杂度选模型档位），不是**RAG 管线层**自适应。后者为 Phase 5.4 预留，设计锚点如下：

- **入口**：`RetrievalAugmentationAdvisor` 之前增加查询复杂度分类器（轻量 LLM 或规则：查询长度 + 是否含知识库领域词 + 会话状态）；
- **分流**：简单查询（寒暄、元问题、明显库外问题）跳过检索链路直连 LLM——节省 embedding/rerank 开销、降低 TTFT；复杂查询走完整 RAG 管线；
- **落点**：实现形态为 Order 440 的 `QueryRoutingAdvisor`（before 阶段向上下文写入 `skip_retrieval` 标记，`RetrievalTraceAdvisor`/`RetrievalAugmentationAdvisor` 读标记短路），不改动现有 Advisor 链结构；
- **度量**：分流比例与两路的回答质量分别进入 kb-eval 统计（第十六章），防止"为省 token 而过度跳过检索"。

> 2026 行业共识：60-70% 的简单查询不需要完整 RAG，但无路由的固定管线同样过时——自适应路由是两者的中道，故不提前到 Phase 2（缺乏评估基线时分流质量不可控，先建 2.16 度量再谈路由）。
