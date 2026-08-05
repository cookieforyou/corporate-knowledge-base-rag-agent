# 第十一章：Agent 对话链路

> 本章为《企业知识库 RAG Agent 工作台：Spring AI 2.0 全景实现报告》v2 拆分版的一部分（原第五卷「核心模块技术实现」）
>
> [📑 返回目录](./README.md) · 最后更新：2026-07-31
>
> **v2 修订**：① 11.1 核心 Advisor 由手搓 `PrefetchRagAdvisor` 改为第十章的 `RetrievalAugmentationAdvisor` 组装 + 瘦 `RetrievalTraceAdvisor`；② 全部虚构 API 修正为 2.0.0 GA 真实 API（`ChatClientRequest.from()`、`ToolContext.requestApproval()`、`RedisChatMemory`、`ToolRegistry.merge()` 等，详见各节 v2 注）；③ MCP 传输 SSE → Streamable HTTP。
>
> **v2.3 修订（2026-08-04，3.1 实现期）**：① Redis 会话记忆仓储修正为 2.0 GA Jedis 形态（`RedisChatMemoryConfig`，starter 坐标 `spring-ai-starter-model-chat-memory-repository-redis`，前缀 `spring.ai.chat.memory.redis.*`）——v2 草图的 RedisTemplate 构造器不存在；② Agent 对话链定稿为独立 `agentChatClient` Bean（记忆 Advisor 缺失 CONVERSATION_ID 为硬断言，不可挂评估共享 Bean）；③ 新增 FaultTolerantChatMemory 容错装饰、kb_session/kb_message PG 归档旁路、sessionId 会话协议；④ 2026-08-05 E2E 追加：自动配置条件让位陷阱（用户 ChatMemory Bean 致 Redis 仓储静默回退 InMemory）——RedisChatMemoryRepository 改为显式装配，详见 11.2 v2.3 注 ⑤。

---

## 11.1 检索增强与溯源透传

RAG 检索与证据注入的完整实现见**第十章**（`RetrievalAugmentationAdvisor` + `HybridDocumentRetriever` + `RrfFusion` + `RerankDocumentPostProcessor` + `ContextualQueryAugmenter`）。本章只定义对话链路上的**溯源透传层**：把检索过程的 trace 数据送到 SSE TRACE 事件与审计日志。

### 11.1.1 RetrievalTraceAdvisor（瘦 Advisor，v2 新增，v2.1 重写）

> **v2.1 实现期重写（2026-08-02）**：v2 原稿有两处结构性错误，E2E 实证后推翻：
> ① **模块边界违反**——草图在 kb-ai-core 引用 kb-api 的 `JwtUtils`（依赖方向 kb-ai-core ← kb-api 不可逆）。身份填充移至 kb-api Controller 的请求线程（JwtUtils 天然可用）；
> ② **请求作用域填充失效**——草图经 `ObjectProvider.getObject()` 在 Advisor 链填充 `@RequestScope` RetrievalContext。实证：MVC 异步请求（SSE 流式）在请求线程返回后即标记请求完结，作用域代理在整个流式生命周期不可解析（`ScopeNotActiveException`），所有填充/读取被守卫静默降级——**租户过滤与 trace 在流式路径实际全部失效**。RetrievalContext 改为每请求纯实例经 advisor 参数传递（定稿机制见 10.2.1），本 Advisor 随之瘦身为纯上下文 Map 操作。

定稿职责：① before 打检索起始时刻戳（调试 API 10.7 / 时延观测）；② after 将 trace 快照写入响应上下文（供**同步**链路调试 API 消费；流式 TRACE 走 Controller 直读同一实例的旁路，见 11.3）。

```java
package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import org.springframework.ai.chat.client.ChatClientRequest;      // record，位于 chat.client 包（非 advisor.api）
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** 检索溯源 Advisor —— Order 450（先于 RetrievalAugmentationAdvisor 的 500） */
@Component
public class RetrievalTraceAdvisor implements BaseAdvisor {

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        Map<String, Object> context = new HashMap<>(request.context());
        context.put("trace_start_ms", System.currentTimeMillis());
        // record 重建：new ChatClientRequest(prompt, context)，无 from()
        return new ChatClientRequest(request.prompt(), context);
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        // advisor 参数随链路透传于响应上下文；无则原样返回（kb-eval 等非 Web 入口）
        if (!(response.context().get(RetrievalContext.CONTEXT_KEY) instanceof RetrievalContext ctx)) {
            return response;
        }
        Map<String, Object> context = new HashMap<>(response.context());
        context.put("rag_trace", ctx.getTraceSummary());       // List<TraceEntry>：双路原始命中 + final 最终序列
        context.put("retrieval_count", ctx.getTraceSummary().size());
        context.put("top_fusion_score", ctx.getTopFusionScore());
        return ChatClientResponse.builder()
            .chatResponse(response.chatResponse())             // v1 误写为 response.response()
            .context(context)
            .build();
    }

    @Override
    public int getOrder() { return 450; }
}
```

> **v1 → v2/v2.1 API 修正汇总**：
> - `ChatClientRequest.from(request).systemText(...).context(...).build()` → **不存在**。`ChatClientRequest` 是 record，仅有 `new ChatClientRequest(Prompt, Map)` 构造器与 `prompt()`/`context()` 访问器；Prompt 级变换用 `Prompt.builder()` / `Prompt.mutate()`。
> - `response.response()` → 实际访问器为 **`response.chatResponse()`**。
> - 证据注入（Grounding Prompt 组装）不再手写：由 `ContextualQueryAugmenter` 承担（第十章 10.6）。
> - 请求状态传递一律经 advisor 参数（`spec.param` → 请求上下文 → Query.context），不用 @RequestScope/ThreadLocal（v2.1 实证，见 10.2.1）。

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

> **v2.3 实现期修正（2026-08-04，3.1 落地实证）**：v2 草图两处失效，按 2.0.0 GA 字节码核验回写：
> ① **Redis 仓储形态**——`new RedisChatMemoryRepository(redisTemplate)` 在 2.0 GA 不存在：
> Redis 仓储改为 **Jedis 形态**，经 `RedisChatMemoryConfig`/builder（jedisClient/indexName/
> keyPrefix/timeToLive/initializeSchema）构建；制品为
> `spring-ai-starter-model-chat-memory-repository-redis`（含 Jedis 客户端、Redis 仓储、
> chat-memory 自动配置），自动配置前缀 `spring.ai.chat.memory.redis.*`。
> **密码适配**：自动配置的 jedisClient 仅支持 host/port（无 password/database），
> ECS Redis 带密码——项目以 `ChatMemoryRedisClientConfig` 覆盖 `jedisClient` Bean
> （@ConditionalOnMissingBean 让位），连接信息（host/port/password/database）统一取自
> `spring.data.redis.*`，与 Redisson（ETL 进度通道）单一来源；`spring.ai.chat.memory.redis.*`
> 仅保留记忆专属配置（index/prefix/TTL/initialize-schema）。
> 依赖 Redis JSON + Query Engine（Redis 8 内置，首跑 E2E 核验）。
> ② **Bean 拆分定稿**——记忆 Advisor **不挂**共享 `chatClient` Bean：
> `BaseChatMemoryAdvisor.getConversationId()` 对缺失 CONVERSATION_ID 是 Assert 硬断言
> （非静默跳过），kb-eval 注入 `chatClient` 且不传会话 ID，挂上即整体抛错。
> 定稿为独立 `agentChatClient` Bean 承载生产对话链（记忆+溯源+检索），
> `chatClient` 保持纯 RAG 供评估度量，Phase 2 基线持续有效。
> ③ **补充机制**——`FaultTolerantChatMemory` 装饰器：记忆读失败→空历史、写失败→丢弃，
> Redis 抖动不击穿问答主链路（与检索单路降级/rerank 降级同策）；
> `kb_session`/`kb_message` PG 归档为独立旁路（ChatSessionService 异步），
> 补齐 kb_feedback 外键与历史会话列表的数据依赖；消息窗口 maxMessages 默认 20（≈10 轮）。
> ④ **已知限制**——RewriteQueryTransformer 仅见当前 query 文本（历史在 Prompt 消息中），
> 多轮指代消解依赖生成侧上下文；检索侧改写注入历史为后续增强项。
> ⑤ **自动配置条件让位陷阱（2026-08-05 E2E 实锤）**：`RedisChatMemoryAutoConfiguration#redisChatMemory`
> 的 `@ConditionalOnMissingBean` 同时检查 {RedisChatMemoryRepository, ChatMemory, ChatMemoryRepository}
> 三类型——用户定义的 `agentChatMemory`（ChatMemory 型）先于自动配置注册，Redis 仓储 Bean
> 静默让位，`ChatMemoryAutoConfiguration` 回退 **InMemoryChatMemoryRepository**：多轮对话表面连贯
> （进程内记忆），Redis 无索引无键、重启失忆、全程零报错。定稿：`ChatMemoryRedisClientConfig`
> **显式装配 RedisChatMemoryRepository**（用户 Bean 不参与条件让位评估），防回归测试
> `ChatMemoryRedisWiringTest`（ApplicationContextRunner 复刻生产拓扑断言仓储类型）。
>
> **v2.6 实现期修正（2026-08-05，3.7/3.8 配额护栏落地）**：
> ① **租户身份来源**——表注「AuthAdvisor(20) 写入 tenant_id」的前提不成立（3.9 落地形态为
> Controller 入口身份守卫，非 Advisor）：RateLimit/TokenBudget 直接从请求/响应上下文读取
> `RetrievalContext.CONTEXT_KEY` 取 tenantId——与检索/记忆同款参数链（advisor 参数双向透传，
> RetrievalTraceAdvisor.after() 读响应上下文为同源先例）；
> ② **Redis 故障 fail-open**——限流/预算是可用性管控与成本追踪，不是安全边界：Redis 故障
> before 降级放行 / after 丢弃计数 + 告警日志，不击穿问答（FaultTolerantChatMemory / rerank
> 降级同策）；缺租户上下文同样放行——生产链路 Controller fail-closed 守卫保证此处必有租户，
> 该分支仅为防御纵深；
> ③ **令牌桶配置写入**——进程对每租户首次触达以 `setRate` 覆盖式写入（配置为单一事实源，
> Redis 残留旧配置随重启刷新），弃草稿 `trySetRate` 形态（配置变更后 Redis 旧速率滞留）；
> 调用形态定稿 `RateLimiterArgs.of(RateType, rate, interval)`（4.6.1 E2E 实证）；
> 桶口径 `RateType.OVERALL`（同租户多实例共享配额），key `rag:ratelimit:tenant:{tenantId}`；
> ④ **HTTP 状态码定稿**——`RATE_LIMITED`/`TOKEN_BUDGET_EXCEEDED` 统一 **429**
> （GlobalExceptionHandler 配额码集合，区别于一般业务错误 400）；流式路径不经异常处理器，
> 由 AgentController onErrorResume 承接为 SSE ERROR 事件（与 PROMPT_INJECTION 同形态）；
> ⑤ **草图 SINGLE_REQUEST_BUDGET 不实现**——单次请求 token 上限已由模型侧 max-tokens
> （application-ai.yml）硬约束，重复设限无增益；
> ⑥ **Usage 形态实证**——2.0 GA `Usage.getTotalTokens()` 返回 **Integer 可空**（非原始 long），
> 计量须判空；流式 usage 需 `stream_options.include_usage` 随末块下发，当前自动装配的
> deepSeekChatModel 未开启——**流式消耗暂不计账**（同步路径计量完整），开启涉及模型装配
> 变更，列为后续增强项；
> ⑦ **指标形态**——`rag.token.total` / `rag.token.budget.rejected` 不带租户标签（避免指标基数
> 膨胀），租户级观测经 Redis 账本键；3.13 AiBusinessMetrics 落地后可迁移。

```java
package com.enterprise.kb.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentChatClientConfig {

    /**
     * 会话记忆（v2.3 定稿）：Redis 仓储（starter 自动配置注入 ChatMemoryRepository）
     * + 滑动窗口 + 容错装饰。显式构建令自动配置的默认 ChatMemory Bean 让位。
     */
    @Bean
    public ChatMemory agentChatMemory(ChatMemoryRepository chatMemoryRepository) {
        return new FaultTolerantChatMemory(
            MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build());
    }

    @Bean
    public ChatClient agentChatClient(
            ChatModel chatModel,                       // @Qualifier("deepSeekChatModel")
            ChatMemory agentChatMemory,
            TokenBudgetAdvisor tokenBudgetAdvisor,     // 3.8（30）
            RateLimitAdvisor rateLimitAdvisor,         // 3.7（100）
            OutputGuardrailAdvisor outputGuardrailAdvisor,   // 3.6（110）
            InputSanitizeAdvisor inputSanitizeAdvisor,       // 3.5（300）
            RetrievalTraceAdvisor retrievalTraceAdvisor,
            RetrievalAugmentationAdvisor retrievalAugmentationAdvisor
            /* Phase 3 待挂：auditTraceAdvisor(10), authAdvisor(20), toolCallingAdvisor(1000) */) {

        return ChatClient.builder(chatModel)
            .defaultSystem("你是企业知识库 RAG Agent 助手。")
            .defaultAdvisors(
                tokenBudgetAdvisor,                    // 30
                rateLimitAdvisor,                      // 100
                outputGuardrailAdvisor,                // 110
                inputSanitizeAdvisor,                  // 300
                MessageChatMemoryAdvisor.builder(agentChatMemory).order(400).build(),
                retrievalTraceAdvisor,                 // 450
                retrievalAugmentationAdvisor           // 500
            )
            .build();
    }
}
```

> **v2 注（会话 ID）**：多轮对话的会话标识通过 advisor 参数传递，键为 `ChatMemory.CONVERSATION_ID`（值 `"chat_memory_conversation_id"`，经核验 2.0 未变）：`.advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))`。
>
> **v2.3 会话协议**：`/chat`、`/chat/stream` 请求体可选 `sessionId`——前端生成并复用即多轮；缺省后端生成一次性 ID（兼容 Phase 1 单轮）。同步响应回传 `sessionId`；流式协议不变（前端自备）。**缺失会话 ID 为硬失败**（Assert 断言），Controller 层必须保证非空。

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

        // 每请求 trace 实例：请求线程创建并填充身份（v2.1：绝非请求作用域代理，见下方注），
        // 经 advisor 参数传入检索组件，流末直读同一实例
        RetrievalContext trace = newRetrievalContext();   // new RetrievalContext() + JwtUtils 填充 tenantId/userId

        return knowledgeAgent.prompt()
            .user(request.query())
            .advisors(spec -> spec
                .param(ChatMemory.CONVERSATION_ID, sessionId)
                .param(RetrievalContext.CONTEXT_KEY, trace))
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

> **v2 注（流式上下文传递）**：Reactor 流式链路跨线程，Advisor 上下文（`ChatClientResponse.context()`）在流末不易取回，故溯源数据改由 `RetrievalContext` 旁路传递——与检索过滤器共用同一机制（10.2.1），一处设计两处复用。前端对 SSE 的消费协议与 Phase 1 兼容（`data: {"token":"..."}` 追加，新增 `event: TRACE` 命名事件，旧前端忽略即可）。
>
> **v2.1 实现期修正（2026-08-02）**：旁路实例必须是 **Controller 请求线程创建的纯对象**，不可经 `ObjectProvider.getObject()` 取请求作用域代理——MVC 异步请求在请求线程返回后即标记请求完结，代理在流末 reactor 线程解引用必抛 `ScopeNotActiveException`（三轮 E2E 实证，详 10.2.1）。参数化传递后该陷阱根除。另：TRACE 供应商须容错降级（溯源失败不得击穿 SSE 流），TOKEN/DONE 照常到达。

---

## 11.4 自适应路由预留（Phase 5.4 设计锚点）

11.2.2 的 `SmartRoutingChatModel` 是**模型层**路由（按复杂度选模型档位），不是**RAG 管线层**自适应。后者为 Phase 5.4 预留，设计锚点如下：

- **入口**：`RetrievalAugmentationAdvisor` 之前增加查询复杂度分类器（轻量 LLM 或规则：查询长度 + 是否含知识库领域词 + 会话状态）；
- **分流**：简单查询（寒暄、元问题、明显库外问题）跳过检索链路直连 LLM——节省 embedding/rerank 开销、降低 TTFT；复杂查询走完整 RAG 管线；
- **落点**：实现形态为 Order 440 的 `QueryRoutingAdvisor`（before 阶段向上下文写入 `skip_retrieval` 标记，`RetrievalTraceAdvisor`/`RetrievalAugmentationAdvisor` 读标记短路），不改动现有 Advisor 链结构；
- **度量**：分流比例与两路的回答质量分别进入 kb-eval 统计（第十六章），防止"为省 token 而过度跳过检索"。

> 2026 行业共识：60-70% 的简单查询不需要完整 RAG，但无路由的固定管线同样过时——自适应路由是两者的中道，故不提前到 Phase 2（缺乏评估基线时分流质量不可控，先建 2.16 度量再谈路由）。
