# 第十一章：Agent 对话链路

> 本章为《企业知识库 RAG Agent 工作台：Spring AI 2.0 全景实现报告》v2 拆分版的一部分（原第五卷「核心模块技术实现」）
>
> [📑 返回目录](./README.md) · 最后更新：2026-09-03 · v2.96（Spring AI 2.0.0 → 2.0.1 升级适配——Redis 记忆 autoconfigure 三迁移，§11.2）
>
> **v2.96（2026-09-03，依赖升级批：Spring AI 2.0.1）**：Redis 会话记忆 autoconfigure 模块重组，**三迁移**——① 包路径 `org.springframework.ai.model.chat.memory.redis.autoconfigure` → `…model.chat.memory.repository.redis.autoconfigure`（插 `repository` 层）；② 类名 `RedisChatMemoryProperties` → `RedisChatMemoryRepositoryProperties`、`RedisChatMemoryAutoConfiguration` → `RedisChatMemoryRepositoryAutoConfiguration`；③ **配置前缀** `spring.ai.chat.memory.redis.*` → `spring.ai.chat.memory.repository.redis.*`（自动配置 Properties Bean 含旧前缀 Binder 回落，属过渡机制——新前缀任一属性命中即不回落，勿混用；application-ai.yml / kb-eval application.yml / ChatMemoryRedisWiringTest 同批迁新，不留过渡依赖）。**机制不变**：redisChatMemoryRepository 的 @ConditionalOnMissingBean 三类型让位（显式装配策略与 ChatMemoryRedisWiringTest 回归继续有效，312 测试绿）；Builder API（jedisClient/indexName/keyPrefix/timeToLive/initializeSchema）签名未变。**jedisClient 覆盖理由更新**：2.0.1 Properties 补 username/password（自动配置已支持密码认证），但 database 仍缺（REDIS_DB 必须 0 坑位⑦ 语义不变）+ 连接参数单一来源纪律——覆盖 Bean 保留。新增可选字段 maxConversationIds / maxMessagesPerConversation / metadataFields 未消费（缺省即原行为）。全模块编译零破坏面（仅此一处包迁移）。
>
> **v2.77（2026-09-01，模型层批B）**：主模型双形态开关（§11.2.2）——`rag.routing.primary.provider` = glm（缺省，GLM-5.3-Flash 强制思考不可关，reasoning_effort low/high/max 透传）| deepseek（回落，思考缺省显式关闭——官方实证默认开 + effort high 且思考模式静默忽略采样参数，历史形态 temperature 0.1 从未生效）；互斥条件装配 + `primaryChatModel` 桥 + 非法值启动失败，切回只动一个环境变量。**轻任务挂备**：意图路由(440)/查询改写挂 fallbackChatModel（局部 Builder 不注册 Bean，观测四参对齐；改写模型恒定 → 主模型切换不再引起检索形态漂移）。deepseek starter 退役（让位测试删除，`ProviderSwitchWiringTest` 三态接棒）。主答切换触发全量门禁复跑（E1；基线形态 = 默认 hybrid 探针，v2.92 措辞勘误）。
>
> **v2.76（2026-09-01，辅助模型换代）**：备用模型 qwen3.7-plus → qwen3.8-flash（§11.2.2）——百炼 OpenAI 兼容端点 / DASHSCOPE_API_KEY / enable_thinking=false 机制不变，仅模型名切换；`rag.routing.fallback.model` 缺省与 L2 二判载体（§12.11）、Judge 基座（16 章 v2.90）同批换代。接管质量从 plus 档降为 flash 档属成本/质量取舍（故障接管瞬态场景可接受）；门禁基线 md1-final-2 的 L2 力判即经 qwen3.8-flash 备用链（快照实证），读数连续。
>
> **v2.75（2026-08-26，Phase 5 簇③ 5.6 E2E 热修二）**：用户侧失效演练实证暴露
> **坑位㉟**——`invalidateByDocument` 的 TAG 反查 `@docIds:{…}` 直接嵌入原始值，
> RediSearch TAG 表达式内 `-` 具否定符语法含义，文档引用为 UUID 形态（含连字符）
> 即「Syntax error at offset N near …」，按文档失效失败回落（TTL 兜底仍生效）→
> 查询侧 `escapeTagValue` 逐字符反斜杠转义（存储侧原值不动，§11.9 热修二段）。
>
> **v2.74（2026-08-25，Phase 5 簇③ 5.6 E2E 热修）**：用户侧簇③-E2E 首跑实证暴露
> 批1 两处缺口并修复（§11.9 末「E2E 热修」段）：① **坑位㉝**——Redisson 4.6.1
> `hasIndex` 经 Lua 包裹 `FT.INFO` 判存在，仅错误文案匹配 "not found"/"no such
> index" 判不存在；Redis Stack / RediSearch 对不存在索引返回 "Unknown index
> name"——文案不匹配即上抛异常，租户索引惰性创建永不执行（查找/写入全程
> fail-open 直通）→ 服务侧补认该文案族为「不存在」；② `SemanticCacheProperties`
> 缺 Bean 注册致启用态启动失败（**坑位㉞**）→ 补 `@Component`（同
> `ParsingProperties` 先例）。
>
> **v2.73（2026-08-25，Phase 5 簇③ 5.6 批3：N1 定案收口）**：新增 §11.10——供应商侧
> Context Cache（N1，复审定案纳入项）按 2026-08-24 用户裁决**收窄为 DeepSeek 侧**：
> 官方契约核验（磁盘级前缀缓存默认开启零代码、命中 ≈10% 计费、官方最小前缀 64
> tokens、V4-Flash 社区实测 256 门槛待官方确认）+ 主链固定前缀体量实测（单轮 =
> RAG_SYSTEM_PROMPT 73 字符 + GROUNDING 静态头 426 字符 ≈ 499 字符，估算越过双
> 门槛，精确读数经 E2E `usage.prompt_tokens` 实测）+ 遥测面裁决（Spring AI 标准
> Usage 映射不携带 `prompt_cache_hit_tokens`——应用层不新增映射耦合，观测面 =
> E2E 直调对照 + DeepSeek 控制台账单）+ 前缀稳定性纪律（Prompt Git Ops 天然契合；
> canary 每进程随机 → 重启重建一次缓存）；百炼侧留口（支持清单不含 qwen3 系列，
> 备用/评估链无缓存，触发条件登记）。簇③（5.6 + N1）机器侧闭环，用户侧 E2E 待回传。
>
> **v2.72（2026-08-25，Phase 5 簇③ 5.6 批2：链序接线与事件失效）**：§11.9 扩充批2
> 落地 + §11.5.1 链序表 460 插入——`CacheCheckAdvisor`（kb-ai-core/cache，直实现
> CallAdvisor+StreamAdvisor，同 RetrievalGateAdvisor 门控形态）挂 rag 链
> 路由(440)/溯源(450)之后、门控(500)之前：命中短路重放（缓存回答单帧下发 + 溯源载荷
> 回填 RetrievalContext——Controller TRACE/审计/归档同形消费，[ref-N] 锚定不破）/
> 未命中流末聚合异步写入（写入门槛：rag 链结构性 + 单轮 + 非闲聊 + 流正常完成 +
> final 证据非空；嵌入向量查找-写入单次复用）；`kb.cache.semantic` 观测 span；
> 事件失效四处接线（ETL COMPLETED 终态帧覆盖 reparse/replace/重建/首次入库 +
> Chunk 编辑/软删/恢复）经 `rag:cache:invalidate` pub/sub 频道（重建经
> ReindexGateway 委派 reparse 同路径，不重复接线）；`@ConditionalOnProperty` 条件
> 装配 + 消费方 `ObjectProvider` 容忍（关闭态链形态零变化）；全反应器 817 单测绿（+25）。
>
> **v2.71（2026-08-24，Phase 5 簇③ 5.6 批1：语义缓存核心）**：新增 §11.9——
> 查询级语义缓存 `SemanticCacheService`（kb-ai-core/cache）：Redis 8 内建查询引擎
> （FT.* VECTOR HNSW/COSINE，GA 起合入开源核心）经项目既有 Redisson `RSearch`
> 类型化 API 消费（复审定案否决 Spring AI redis 向量存储模块——底层 Jedis 与
> Redisson 双客户端），零新增依赖；每租户一索引域隔离（跨租户命中结构性不可达）+
> KNN top-1 余弦阈值命中判定 + 问句指纹确定性键幂等写入 + 按文档 TAG 反查失效 +
> 启动期能力探测自关 + 全路径 fail-open（缓存是优化件不是防线）；
> 13.3 预留位 `rag.retrieval.cache.hit` 启用（+miss/invalidated）；`rag.cache.*`
> 配置族（缺省关，同族纪律）；批2 落 `CacheCheckAdvisor` 链序接线与事件失效。
>
> **v2 修订**：① 11.1 核心 Advisor 由手搓 `PrefetchRagAdvisor` 改为第十章的 `RetrievalAugmentationAdvisor` 组装 + 瘦 `RetrievalTraceAdvisor`；② 全部虚构 API 修正为 2.0.0 GA 真实 API（`ChatClientRequest.from()`、`ToolContext.requestApproval()`、`RedisChatMemory`、`ToolRegistry.merge()` 等，详见各节 v2 注）；③ MCP 传输 SSE → Streamable HTTP。
>
> **v2.3 修订（2026-08-04，3.1 实现期）**：① Redis 会话记忆仓储修正为 2.0 GA Jedis 形态（`RedisChatMemoryConfig`，starter 坐标 `spring-ai-starter-model-chat-memory-repository-redis`，前缀 `spring.ai.chat.memory.redis.*`）——v2 草图的 RedisTemplate 构造器不存在；② Agent 对话链定稿为独立 `agentChatClient` Bean（记忆 Advisor 缺失 CONVERSATION_ID 为硬断言，不可挂评估共享 Bean）；③ 新增 FaultTolerantChatMemory 容错装饰、kb_session/kb_message PG 归档旁路、sessionId 会话协议；④ 2026-08-05 E2E 追加：自动配置条件让位陷阱（用户 ChatMemory Bean 致 Redis 仓储静默回退 InMemory）——RedisChatMemoryRepository 改为显式装配，详见 11.2 v2.3 注 ⑤。
>
> **v2.9 双链路拆分定稿（2026-08-05，任务 3.19）**：新增 §11.5——RAG 问答与 Tool 问答双链路拆分 + kb-ai-agent 模块独立（动因三痛点实证、模块/Bean 形态、mode 路由协议、SSE/toolContext 矩阵、与 §11.4 5.4 锚点正交关系、后续演进）；§11.2 单链装配草图标记为历史形态。
>
> **v2.10 审计落地定稿（2026-08-05，任务 3.12）**：新增 §11.6——AuditTraceAdvisor(order 10) 被拒请求捕获机制（覆写 adviseCall/adviseStream，草图 before+after 形态无法覆盖内层抛错）、双链路数据面（mode/tool_calls + kb_audit_log 四列扩展）、字段数据源与脱敏（query_text 同款 sanitize、rewritten_query 装饰器捕获、流式聚合不缓冲）、旁路容错与异步落库。
>
> **v2.13 修订（2026-08-08，5.4 收窄版提前落地）**：§11.4 rag 链内免检索短路提前实现——QueryRoutingAdvisor(440) 双层分类（L1 正则快路 + L2 结构化 LLM，分类与改写合并单次调用）+ RetrievalGateAdvisor(500) 组合式门控（源码核验 RetrievalAugmentationAdvisor 为 final 类，草图「Advisor 自身读标记短路」形态不可行，修正为门控包裹旁路）；fail-open 纪律、rag.routing.chitchat/knowledge 度量、闲聊路径免 TRACE 帧；mode 跨链契约不变（自动跨链路由仍预留）。§11.5.1 链序表同步更新。
>
> **v2.14 用户反馈闭环（2026-08-08，任务 3.17）**：DONE 帧 JSON 化承载 messageId/traceId + 两 ID 前移 Controller 请求线程；反馈 API（upsert 可改评）+ kb_audit_log.feedback 回填。详见 §11.3/§11.6.3 v2.14 注。
>
> **v2.15 [ref-N] 引用编号缺陷修复（2026-08-09）**：ContextualQueryAugmenter 默认拼接不编号致引用编号漂移（抄正文圈号/越界/错位），编号化 documentFormatter 确定化锚点 + 提示词 ASCII 契约 + 前端圈号兜底。详见 §11.1.2 v2.15 注。
>
> **v2.16 徽标内联渲染修复（2026-08-09）**：前端旧版按 [ref-N] 切段逐段渲染——各段被包成块级 `<p>`，徽标独占一行、邻接标点/表格行被切断成孤儿行；改占位符单次渲染管线（[ref-N]→@@REFN@@ 透明 token → 全文单次渲染 + sanitize → 换回徽标），徽标内联。纯展示修复，契约不变。详见 §11.1.2 v2.16 注。
>
> **v2.17 历史会话列表与恢复（2026-08-09，3.15 清单缺口补齐）**：新增 §11.7——归档时写 kb_message.citations（预留列启用，SSE TRACE 同形载荷，[ref-N] 对齐契约天然保持）；会话三端点（列表/消息/删除，tenant+user 双过滤 fail-closed，附录 C `/api/v1/agent/*` 锚点落地为扁平路径）；过期会话续聊记忆回填（chat 入口前置，PG 重建窗口 + SETNX 单发守卫 + fail-open）；前端对话页内可收起会话栏，历史消息复用现有渲染/溯源/反馈链路。
> **v2.17.1（2026-08-09，E2E 修复）**：删除带反馈会话外键违例——kb_feedback.message_id 无级联，删除会话须同事务先清反馈（§11.7.2 DELETE 行）。
>
> **v2.61（2026-08-22，Phase 4 簇⑦ 批2——4.8 Prompt Git Ops 专类收编）**：对话链全部 Prompt 模板收编至单一事实源 `com.enterprise.kb.ai.prompt.PromptTemplates`（kb-ai-core，9 条：GROUNDING_PROMPT / INDIRECT_WARNING_NOTE / EMPTY_CONTEXT_PROMPT / HISTORY_REWRITE_PROMPT / INTENT_CLASSIFIER_PROMPT / INJECTION_JUDGE_PROMPT / RAG_SYSTEM_PROMPT / EVAL_SYSTEM_PROMPT / TOOL_SYSTEM_PROMPT）——原散落 6 处常量（RetrievalConfig ×3 + QueryRoutingAdvisor + SemanticInjectionAdvisor 分类器 + ChatConfig/Rag/Tool 三处 defaultSystem）全部改引用；解析链语境增强模板收编于 `com.enterprise.kb.etl.prompt.PromptTemplates`（kb-etl 不依赖 kb-ai-core 的架构约束，用户定案每模块一专类）；kb-eval Judge Prompt 既有 `JudgePrompts` 专类形态零改动。**Git Ops 纪律**：模板增删改一律经专类，`git log` 即版本史，消费方禁内联（PromptTemplatesTest / RetrievalConfigContextFormatTest 契约钉死）。外部化配置率 100% 达成（第 18 章验收，18.2 注记同步）。

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

> **v2.15 修正（2026-08-09，3.17 E2E 发现的引用编号缺陷）**：仅靠 prompt 约定 `[ref-N]` 不够——源码核验 `ContextualQueryAugmenter` 默认 `documentFormatter` **仅以换行拼接文档文本、不编号**（spring-ai-rag 2.0.0），模型面对无编号拼接文本只能猜测 N，E2E 实证三类漂移：① 抄用文档正文序号符号（DDD 文档圈号标题「⑤」被抄成 `[ref-⑤]`，前端 ASCII 正则 `/\[ref-(\d+)\]/g` 不匹配 → 徽标不渲染、不可点）；② 越界编号（Top-K=5 却出现 `[ref-6]`，点击落空「溯源数据未就绪」）；③ 编号与注入顺序错位（点错文档卡片）。修复三层：
> 1. **编号化 `documentFormatter`**（根治，`RetrievalConfig.formatNumberedContext`）：每条资料前缀独立 `[ref-N]` 编号行（N=1 起始列表下标），引用锚点确定化；编号顺序即重排后 final trace 序列，与 SSE TRACE / 前端 `chunks[N-1]` 对齐关系不变；
> 2. **提示词显式契约**：`GROUNDING_PROMPT` 明确「编号只能取自资料编号行、只用阿拉伯数字、禁 ①②③ 等圈号与正文序号、禁引未给出的编号」；
> 3. **前端兜底归一**（`markdown.ts`）：仅对 `[ref-①…⑳]` 形态定点归一为 ASCII（U+2460 偏移换算），正文圈号内容不动——概率性残留的确定性兜底。
>
> 教训：**「模型输出格式约定」必须有确定性锚点支撑**——prompt 里的编号契约若无注入侧编号配合，等于让模型自由发挥。
>
> **v2.16 修正（2026-08-09，3.17 E2E 后续验证发现的展示缺陷）**：前端旧版 `renderAnswer` 按 `[ref-N]` 切段、每段独立渲染 markdown——每段被 marked 包成块级 `<p>`，内联徽标夹在块级元素之间**独占一行**，ref 邻接的句号、含 ref 的表格行被切成孤儿行（截图实锤：孤立的「。」与「|」）。改为**占位符单次渲染管线**：`[ref-N]` → `@@REFN@@` token（`@` 非 markdown 元字符，对 marked 与 DOMPurify 均透明，含表格单元格/列表项内联存活经无头验证）→ 全文单次渲染 + sanitize → sanitize 后换回徽标。徽标保证内联于段落、表格/列表不再被切断；「防 markdown 吞括号、防消毒器剥徽标」原意图同持（markdown 见不到方括号语法、消毒器见不到徽标）。纯展示修复：[ref-N] 契约、data-ref 对齐与点击行为不变。
>
> **v2.79 修正（2026-09-02，MB1 全量门禁 CA/HR 双破线第一轮治理）**：主模型切 GLM-5.3-Flash effort low 后，规则 3 弱措辞「每个事实性陈述附 [ref-N] 标注」实测退化——`citationResolvableRate` 两轮均 1.000（编号格式契约完好，v2.15 治理仍有效），但**来源支撑**崩塌：CA 0.890→0.761（<0.85 破线）、HR 4.2%→6.8%（>5% 破线），NOT_SUPPORTED 恶化 16 例与 HR 恶化例完全同批；退化集中于 REASONING/MULTI_DOC 断言高密分类，FACTOID/TABLE 稳，AC 反升 4.642→4.725——**非内容质量与格式问题，是引用行为纪律问题**（effort low 思维链短 → 先答后引/末尾集中补标 → 编号错挂，与裸断言漏标）。规则 3 细化为三条行为契约：① **时机与位置**——事实性陈述句末紧跟依据资料的 [ref-N]，写完一句即标，不得答完后末尾集中补标；② **归属**——陈述源自哪条就标哪个，多资料综合逐句分别标注；③ **支撑**——无依据支撑的内容不得作为事实陈述写入（与 Judge 来源支撑口径「内容被任一条目支撑即可」对齐）。三锚点由 `PromptTemplatesTest.groundingPromptPinsCitationDiscipline` 契约钉死防回退。生效面：`chatClient`（eval 被测链 hybrid/chain 探针）与 `ragAgentChatClient`（生产）同挂 `RetrievalAugmentationAdvisor` 同源消费，NRob 对照路径同模板——单点改动三处生效，eval 读数与生产行为同步迁移。归因边界：temperature 1.0（GLM 思考态官方推荐值且生效，与 DeepSeek 思考态静默忽略不同）为次要变量非主因——若本轮 CA/HR 仍破线，第二轮消融 `ZHIPU_TEMPERATURE=0.6` 单变量归因（治理路线 16 章 v2.93）。另：模板静态头部变更触发的 N1 前缀缓存重建一次为预期内代价（§11.10 前缀稳定性纪律）。

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
> **2.0.1 迁移（v2.96）**：前缀改为 `spring.ai.chat.memory.repository.redis.*`
> （插 repository 层），Properties/AutoConfiguration 类随包重组更名；旧前缀仅
> 自动配置 Binder 过渡回落。jedisClient 覆盖理由收窄为 database 缺失 +
> 单一来源纪律（2.0.1 起自动配置已支持 username/password）。
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
>
> **v2.9 装配形态更新（2026-08-05，任务 3.19）**：上方单 `agentChatClient` 草图已被**双链路拆分**取代——`ragAgentChatClient`（kb-ai-core，纯检索零工具）+ `toolAgentChatClient`（kb-ai-agent，纯工具零检索），请求体 `mode: rag|tool` 显式分流；下方草图代码块为历史形态，定稿见 **§11.5**。

### 11.2.1 @Tool 工具调用与 Human-in-the-Loop（v2 重写）

> **v1 重大错误修正**：`ToolContext.isApproved()` / `ToolContext.requestApproval(String)` **完全虚构**。`ToolContext` 仅有 `getContext()`（不可变 Map）。Spring AI 2.0 没有内建的工具审批 API，HITL 需按下列真实机制组合实现。
>
> **v2.8 实现期定稿（2026-08-05，任务 3.3/3.4 落地）**：
> ① **Mock 工具层先行（复审定稿）**——真实 OA/ERP/数据库开发环境不可达，
> `EnterpriseMockTools` 契约先行（签名/描述/返回结构对齐真实系统，后续逐个替换
> 服务客户端不动链与机制）：读工具 queryEmployee/queryLeaveBalance 自动执行 +
> 写工具 submitLeaveRequest 走 HITL；仅挂生产链 agentChatClient，kb-eval
> chatClient 无工具、评估基线不受影响；
> ② **ToolCallingAdvisor 装配实证**——2.0 GA ChatClient 存在工具回调时自动挂
> ToolCallingAdvisor，但 DEFAULT_ORDER = HIGHEST_PRECEDENCE+300（链**最外层**）——
> 工具循环每轮重新穿越全部内层 Advisor（限流/预算配额按迭代消耗、记忆/检索重复
> 执行）；定稿自建实例 **advisorOrder(1000)**（本表设计位，最内层只包裹模型调用），
> 自建 ToolAdvisor 后 autoRegisterToolCallingAdvisor 检测已有即让位（源码核验）；
> ③ **复审四要素落地形态**——要素①：approvalId Redis 账本
> `rag:tool-approval:{approvalId}`（RMap&lt;String,String&gt; 字符串键值规避 codec 依赖）：
> TTL（`rag.tool.approval.ttl-minutes` 默认 10 分钟，approve 续期）+ **一次性消费**
> （consume 校验通过即删键）+ 创建时绑定 tenant/user、approve/consume 均校验绑定
> （跨租户/跨用户不可用，防重放/防越权），弃草稿弱键 `approval:{employeeId}:{leaveType}`；
> 要素②：确认态经 `.toolContext(Map)` 通道——与 advisor 参数独立的第二条通道
> （进 ToolCallingChatOptions 随工具执行注入 ToolContext），ChatService 统一组装
> （身份 + RetrievalContext 实例 + approvedToolCallId 条件写入，ChatClient 断言
> toolContext 无 null 值）；要素③：SSE 新增 **TOOL_CALL** 命名事件（流末先于 TRACE，
> 有记录才推送）+ 同步响应追加 toolCalls 字段；要素④：Mock 工具层即本注①；
> ④ **fail-closed**——身份不完整写工具不创建审批单不落写；Redis 故障 create/consume
> 抛 APPROVAL_STORE_UNAVAILABLE、写工具拒绝执行（宁可不可用不可绕过审批）；
> 读工具不经审批服务不受影响；
> ⑤ **工具调用记录**经 toolContext 写回 RetrievalContext（与 trace 同款参数链），
> Controller 流末投影 TOOL_CALL / 同步响应 toolCalls；审批 API
> `POST /api/v1/tools/approvals/{approvalId}/approve`（身份守卫同 3.9）；
> ⑥ **确认轮确定化加固（E2E 实测发现）**——工具调用是模型的自主决策，
> approvedToolCallId 仅经 toolContext 对工具可见、模型上下文不可见——实测确认轮
> 存在模型不调工具（被检索上下文带偏成普通 RAG 作答）的概率，「凭证无效」等
> 沙箱分支因此无机会触发（安全不变量不受影响：未 approve 的写操作未执行）。
> 加固：携带 approvedToolCallId 时 ChatService 注入 system 指令提示模型调用写
> 工具完成执行（指令不落记忆，system 消息不进记忆窗口）。

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

> **v2.7 实现期定稿（2026-08-05，任务 3.2 落地，用户拍板）**：
> ① **实用形态**——v1 设计的 ECONOMY/STANDARD/PREMIUM 三级复杂度路由在「主 + 备」
> 双模型现状下三档形同虚设，且复杂度分类器引入误路由风险——**复杂度分级移交
> Phase 5.4 查询意图识别统一做**；本期落地验收相关形态：主模型故障自动切换备用
> （验收「Failover 切换时间 < 5s」）+ 熔断器保护（v1 的轮询/层级降级不实现）；
> ② **备用模型定稿 qwen3.7-plus**——百炼 OpenAI 兼容端点，跨厂商容灾（DeepSeek
> 平台级故障也能扛），装配与 kb-eval JudgeModelConfig 同款实证形态（baseUrl/apiKey
> 经 OpenAiChatOptions 传入，坑位①），凭据经 `rag.routing.fallback.api-key` 默认回落
> DASHSCOPE_API_KEY（yml 单一回落链，注解层不重复嵌套占位符）；
> **思考模式显式关闭（E2E 延迟实证）**：qwen3.5/3.6/3.7 商业版默认开思考模式
> （`enable_thinking=true`，官方文档实证），每次调用先生成大量思维链——E2E 实测
> 单调用 20-60s，故障接管场景不可接受；以 `extraBody("enable_thinking", false)`
> 经 createRequest 透传请求体顶层（`rag.routing.fallback.enable-thinking` 配置化，
> 默认 false）；
> ③ **拓扑**——`SmartRoutingChatModel implements ChatModel` 包装主模型
> （deepseek starter 自动装配的 deepSeekChatModel）与 `fallbackChatModel` Bean；
> `chatClient`/`agentChatClient` 统一改注 `smartRoutingChatModel` 替代主模型直注——
> 生产链与评估链同时获得容灾，kb-eval 度量语义常态不变；**@Primary 必要性
> （启动失败实证）**：Spring AI `ChatClientAutoConfiguration#chatClientBuilder`
> 按类型裸注入单一 ChatModel（源码核验），多 ChatModel Bean 歧义致启动失败——
> 路由模型标记 @Primary 统一消解（显式 @Qualifier 注入点不受影响）；
> ④ **熔断器三态（无锁原子）**——CLOSED（连续失败 < 阈值）→ OPEN（≥ 阈值且窗口内，
> 请求直发备用、主模型零触达）→ HALF_OPEN（窗口结束后首请求试探主模型：成功闭合
> 清零、失败立即重开窗口续期）；成功即清零失败计数；
> ⑤ **失败即切不丢请求**——CLOSED/HALF_OPEN 态主模型调用抛错时当次请求立即转发
> 备用作答（切换耗时 = 主模型失败暴露耗时 + 备用调用，快速失败类故障远低于 5s）；
> 备用自身失败如实上抛（双模型俱损已无路由可做）；
> ⑥ **流式语义**——主模型流错误经 onErrorResume 切备用流整段重发：首 token 前错误
> （连接/鉴权/配额类故障常态形态）用户无感；极少数已流出部分 token 后中断会内容
> 重复——已知取舍，优于流中断报错；
> ⑦ **配置与降级**——`rag.routing.fallback.enabled=false` 时备用 Bean 不装配，路由
> Bean 透传主模型，单模型形态零行为变化；熔断参数 `rag.routing.circuit.*`；
> ⑧ **跨厂商 options 屏障（E2E 缺陷实证）**——流入路由模型的 Prompt 携带主模型
> options（ChatClient 装配期经路由模型 getOptions() 注入 DeepSeekChatOptions），
> 备用 `OpenAiChatModel.createRequest` 对 `prompt.getOptions()` 是
> `(OpenAiChatOptions)` 强转 + 非空断言（源码核验）——直接转发 ClassCastException。
> 定稿：转发备用前以 `new Prompt(instructions, fallback.getOptions())` 重建换装；
> 代价为请求级自定义 options 转发时丢弃（当前链路无此调用方，已知取舍）；
> ⑨ **手工装配模型的观测接线（E2E 观测实证）**——备用调用不打
> ChatModelCompletionObservationHandler 的 Completion 日志：自动装配的
> deepSeekChatModel 由 starter 注入 ObservationRegistry，手工
> `OpenAiChatModel.builder()` 不继承——builder 显式
> `.observationRegistry(...)`（ObjectProvider + NOOP 兜底）后恢复。

> **v2.19 修正（2026-08-11，簇③ D1 主模型装配切换 + 流式计账）**：
> ① **缘由**——deepseek starter 的 `DeepSeekApi.ChatCompletionRequest` record
> 无 `stream_options` 字段、`DeepSeekChatOptions` 无 streamUsage（2.0.0 jar
> 字节码核验），无法开启 include_usage，流式消耗系统性漏算（配额账本 + 审计
> token 列双面）；
> ② **定稿**——`deepSeekChatModel` 改由 SmartRoutingConfig 手工装配为
> `OpenAiChatModel`（DeepSeek OpenAI 兼容端点，base-url 与 starter 默认一致），
> options 开 `streamOptions.includeUsage(true)`；备用模型 options 同步开启——
> 故障接管期间计量不中断（转发经 retargetToFallback 换入备用自身 options）；
> Bean 名不变，@Qualifier 注入点零感知；`spring.ai.deepseek.*` 键改经 @Value
> 消费，键名与默认值不变；
> ③ **starter 让位机制（实证修正）**——首选「同名用户 Bean 让位自动装配」
> 被 ApplicationContextRunner 实证否决：Boot 4.1 直接抛
> BeanDefinitionOverrideException（同名让位不成立）。正解利用 starter 自动
> 配置类的 `@ConditionalOnProperty(name="spring.ai.model.chat",
> havingValue="deepseek")` 门控——application-ai.yml 该键置 `none` 令其整体
> 不装配。`DeepSeekModelOverrideWiringTest` 双向钉死（chat=none 手工 Bean 胜出
> / 回写 deepseek 同名冲突启动失败）；
> ④ **链路收益零改动**——TokenBudgetAdvisor.after() 与 AuditTraceAdvisor
> 末块 usage 回写逻辑不变，include_usage 开启即生效（末块 usage 缺失仍按 0
> 降级）；maxTokens 经官方 SDK 映射 wire 字段 `max_tokens`（字节码核验），
> 与 starter 时代请求形态一致。

> **v2.77 定稿（2026-09-01，模型层批B：主模型双形态开关 + GLM-5.3-Flash 缺省）**：
> ① **缘由双线**——DeepSeek 0731 版涨价（输出空闲/高峰 ¥4.5/¥9.0 每 M token，
> GLM-5.3-Flash 输出 ¥2.8，有效成本差 1.6×~3.2×）；官方文档实证发现 DeepSeek
> 「思考模式默认打开且 effort 默认 high」（v4-flash 同 pro 映射）且「思考模式
> 静默忽略 temperature/top_p 等采样参数（设值不报错不生效）」——**历史形态主路
> 一直以思考开 high 运行**（主链配置从未显式干预），temperature 0.1 从未生效，
> 现状 TTFT/输出计费本就含思维链（completion_tokens 含 reasoning token）；
> ② **双主模型开关（用户定案：避免暴力替换）**——`rag.routing.primary.provider`
> = `glm`（缺省）| `deepseek`（回落），两载体 Bean 互斥条件装配
> （`deepSeekChatModel`/`glmChatModel`，Bean 名沿用），经 `primaryChatModel`
> 桥 Bean 透传给 smartRoutingChatModel（路由层对开关零感知）；provider 非法值
> 双空桥启动即失败并给出合法值域；配置族 `rag.routing.primary.{glm,deepseek}.*`
> （供应商 env 前缀：ZHIPU_*/DEEPSEEK_*，与 DASHSCOPE_* 同风格）；切回 DeepSeek
> 只动 `RAG_ROUTING_PRIMARY_PROVIDER` 一个环境变量；
> ③ **GLM 形态（缺省主答）**——智谱 OpenAI 兼容端点
> `https://open.bigmodel.cn/api/paas/v4`（openai-java ClientOptions baseUrl
> 逐字拼接 + `/chat/completions` 后缀，字节码核验）；**强制思考不可关**（官方
> 文档二次确认：`thinking.type` 仅支持 `enabled`，传 `disabled` 直接报错）——
> 装配零 thinking 配置即唯一合法默认；`reasoning_effort` 支持 low/high/max
> （缺省 max，其余值报错）经 `OpenAiChatOptions.reasoningEffort` 原生字段透传
> （Builder 自定型签名源码核验），配置缺省空=不传=服务端 max，生产档位经冒烟
> 定档；temperature 缺省 1.0（官方推荐；思考模式采样参数生效性未证，无退化
> 基线——DeepSeek 思考形态同静默）；`stream_options.include_usage` 保持
> （思维链 token 计入 completion_tokens，计量口径与 DeepSeek 思考形态连续）；
> ④ **DeepSeek 思考治理（回落形态）**——`thinking-type` 缺省 `disabled`：官方
> 默认（开 + high）是成本/延迟双高的隐藏负担，回落定位 = 快答形态；关思考后
> 采样参数恢复支持（官方限制条款明确绑定思考模式，DeepSeek V3 非思考形态
> 历来支持 temperature）；如需保留思考置 `enabled` + `reasoning-effort`
> （low/high/max）——thinking 经 extraBody 嵌套透传（OpenAI SDK 无此标准字段），
> effort 走原生字段；
> ⑤ **轻任务挂备（切换必要配套）**——意图路由(440)/查询改写/多查询扩展的
> LLM 调用统一切到 `fallbackChatModel`（qwen3.8-flash 思考关，L2 二判同载体）：
> GLM 强制思考（缺省 max effort）下路由/改写走主模型 = 每问先烧一段思维链，
> TTFT 不可接受；实现为 RetrievalConfig 私有辅助构造局部 `ChatClient.Builder`
> （**不注册 Bean**——自动配置 Builder 挂 `@ConditionalOnMissingBean`，注册
> 自定义会顶掉全局默认致 rag/tool 链断供，ChatClientAutoConfiguration 源码
> 核验），观测四参对齐（registry + 双 convention，`@Nullable` 容忍——
> DefaultChatClientBuilder 源码核验，轻调用 span 保持入树）；备用缺席（单模型
> 形态）回落按类型注入的主链 ChatModel——kb-eval IT 桩上下文按 @Primary 解析
> 自动正确（消费点类零改动）；**正效应**：改写模型恒定后主模型切换不再引起
> 检索形态漂移；熔断计数从此只反映生成调用（路由/改写失败不再污染主模型
> 熔断样本）；qwen 故障时路由 fail-open 回落完整检索（既有纪律）、改写故障
> 上抛——等价于旧形态「主备俱损」场景，无韧性回退；
> ⑥ **deepseek starter 退役**——主模型双形态均手工装配，starter 依赖自
> kb-ai-core pom 移除；`DeepSeekModelOverrideWiringTest`（v2.19 让位机制双向
> 钉死）随让位对象消失而删除，实证记录留档上方 v2.19 注记；`chat: none` 保留
> 为防御位（防未来误引 chat starter）；接棒测试 `ProviderSwitchWiringTest`
> 三态钉死（缺省 glm / 显式 deepseek / 非法值启动失败）；
> ⑦ **门禁复跑（E1 纪律）**——主答模型切换属跨模型漂移，chain 探针门禁须
> 复跑定档（用户侧项，见用户侧待执行项清单）；L2/Judge/图抽取不受影响
> （批A 已对齐 qwen3.8-flash）。

> **v2.78 补充（2026-09-01，MB1 冒烟回传：GLM effort 生产档 low 定案）**：
> 五验冒烟功能全过，但**延迟体感不合格**——GLM 生成两问实测 30.6s / 20.5s，
> 对照 DeepSeek 思考开 high 时代基线 5-8s。**归因（audit CSV + prometheus
> 实证）**：GLM 服务端 max effort 档思维链过长——completion 1048/921 tokens
> （含思维链，吞吐 ~35-45 tok/s），prompt 侧正常（1573-1855），检索仅
> 400-677ms 非瓶颈，轻任务挂备正常（改写未触发，routing knowledge 3/3）。
> **定案**：`ZHIPU_REASONING_EFFORT` 缺省空 → **`low`**（application-ai.yml 回写
> 生产档；high/max 仍可显式调回）——RAG 主答有检索证据锚定，low 档推理深度
> 足用，TTFT 优先。**low 复验（2026-09-02 回传）**：同题重问 7185ms / 8506ms，
> 回归 5-8s 基线带，token 同步回落（无长思维链）——生产档定案闭环。
>
> **v2.95 补充（2026-09-02，MB1 第二轮温度消融：GLM temperature 生产档 0.2 定案）**：
> GLM 思考态采样参数**生效**（与 DeepSeek 思考态静默忽略不同，16 章 v2.93 裁决），
> 缺省自官方推荐 1.0 → **0.2**（门禁消融实证：CA 0.764→0.782 / AC 4.682→4.755 /
> F 4.209→4.364 / MULTI_DOC F 2.111→3.333，HR 持平——全面小胜无负面维度）。
> 温度为次要变量（HR 6.2% 不动，细节保真度归因 effort 档，人审定谳 16 章
> v2.95）。**温度不跨模型对齐**：DeepSeek/qwen 备用 0.1 为各自保守档，量纲
> 不可比。effort high 对照轮（十字路口选项 a）待定案。

### 11.2.3 MCP 工具集成（v2 修订）

> **v2 修正**：① SSE 传输自 Spring AI 2.0.1 起**已废弃**，新接入使用 **Streamable HTTP**；② `ToolRegistry.merge(...)` 不存在——合并多来源工具经 `ChatClient.Builder.defaultTools(provider1, provider2, ...)`。

```yaml
# application.yml - MCP Client 配置（Spring AI 2.0.1 GA）
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
>
> **v2.14 协议修订（2026-08-08，任务 3.17 反馈闭环）**：① **DONE 帧 JSON 化**——无名 DONE 帧 data 由字面量 `[DONE]` 演进为 `{"messageId":"...","traceId":"..."}`（终帧天然携带本轮句柄；错误路径不发 DONE，无归档即无可评价对象）；同步 `/chat` 响应 data 同步增补 messageId/traceId。修订破坏 Phase 1「DONE 数据形状不变」兼容承诺，唯一消费方为自家前端同批改造（用户拍板定案）。② **两个句柄的前移生成**：messageId 由 Controller 请求线程预生成并透传 ChatSessionService 归档复用（保证 kb_feedback.message_id 外键可解析——原 messageId 在异步归档内部生成、从不暴露，是反馈定位的真实缺口）；trace_id 由 Controller 生成经 RetrievalContext.traceId 透传 AuditTraceAdvisor 原样落库（缺省回落自生成，kb-eval 无 ctx 入口不受影响），反馈 API 凭 idx_audit_trace 确定性关联审计行。③ **反馈 API**：POST /api/v1/feedback（messageId+userId upsert 可改评、期望回答、tags；租户/用户归属经 message→session 校验，跨域引用伪装 MESSAGE_NOT_FOUND 不泄露存在性；归档异步竞态由 rag.feedback.message-wait-millis(2000) 短窗轮询兜底）+ GET /api/v1/feedback（Bad Case 查询，经 message→session 子查询租户收敛，附原始问答文本）；kb_audit_log.feedback 回填 POSITIVE/NEGATIVE + kb_feedback.audit_log_id 关联（审计缺失静默跳过）；指标 rag.feedback.like/dislike 接线（3.13 预留点）。

---

## 11.4 自适应路由预留（Phase 5.4 设计锚点）

11.2.2 的 `SmartRoutingChatModel` 是**模型层**路由（按复杂度选模型档位），不是**RAG 管线层**自适应。后者为 Phase 5.4 预留，设计锚点如下：

- **入口**：`RetrievalAugmentationAdvisor` 之前增加查询复杂度分类器（轻量 LLM 或规则：查询长度 + 是否含知识库领域词 + 会话状态）；
- **分流**：简单查询（寒暄、元问题、明显库外问题）跳过检索链路直连 LLM——节省 embedding/rerank 开销、降低 TTFT；复杂查询走完整 RAG 管线；
- **落点**：实现形态为 Order 440 的 `QueryRoutingAdvisor`（before 阶段向上下文写入 `skip_retrieval` 标记，`RetrievalTraceAdvisor`/`RetrievalAugmentationAdvisor` 读标记短路），不改动现有 Advisor 链结构；
- **度量**：分流比例与两路的回答质量分别进入 kb-eval 统计（第十六章），防止"为省 token 而过度跳过检索"。

> 2026 行业共识：60-70% 的简单查询不需要完整 RAG，但无路由的固定管线同样过时——自适应路由是两者的中道，故不提前到 Phase 2（缺乏评估基线时分流质量不可控，先建 2.16 度量再谈路由）。

> **v2.13 提前落地定稿（2026-08-08，用户拍板收窄范围）**：Phase 2 Golden 74 基线建成后分流质量可验证，且 E2E 实证两痛点——① 闲聊/元问题白付改写+检索+重排前置开销（实测 ~5s）；② grounding 强约束（「必须且只能基于【参考资料】」）语义层压过 Memory(400) 注入的历史消息，「我刚才问了什么」被拒答（bm25 召回 10 条噪声未触发空证据模板，是强约束拒答非记忆故障）。**收窄定案：只做 rag 链内免检索短路**，跨链 mode=auto 与复杂度三级模型路由仍留 5.4。落地形态四处修正/强化：
>
> - **双层分类**：L1 正则快路（整句全匹配纯寒暄/致谢/道别/助手元问题，≤15 字符）零 LLM 调用；L2 结构化分类（`entity(IntentResult)`）兜元问题与库外问题；
> - **分类与改写合并单次 LLM 调用**：KNOWLEDGE 路径分类器同步产出消解后查询，预写入 `RetrievalContext.rewrittenQuery`，`RewriteCapturingQueryTransformer` 识别后跳过自身 LLM 调用——知识问零新增延迟（草图未含此优化）；
> - **门控形态修正**：源码核验 `RetrievalAugmentationAdvisor` 为 **final class**，草图「Advisor 读标记短路」不可行——`RetrievalGateAdvisor`(order 500) 实现 CallAdvisor+StreamAdvisor 组合式包裹 delegate，skip 时 `chain.nextCall/nextStream` 直接放行；
> - **fail-open 纪律 + 度量**：分类异常/解析失败/未知 intent 一律回落 KNOWLEDGE（最坏=现状）；`rag.routing.chitchat/knowledge` 计数进 AiBusinessMetrics（3.13 注册中心）；闲聊路径免 TRACE 帧（对齐「不推空帧」纪律）；`rag.routing.intent.enabled` 总开关可回退。
>
> 历史消息来源：分类器直注 agentChatMemory 经 CONVERSATION_ID 自读（不依赖 MessageChatMemoryAdvisor 内部排序；440 时当前轮未入忆，读到纯历史）。kb-eval 独立 chatClient 不挂本 Advisor，评估基线零影响。

---

## 11.5 双链路拆分：RAG 问答与 Tool 问答分离（v2.9 新增，任务 3.19）

> **v2.9 实现期定稿（2026-08-05，用户拍板）**：原 `agentChatClient` 单链揉合 RAG
> 检索与工具调用（8 Advisor + defaultTools），实证三痛点：① HITL 确认轮模型被检索
> 上下文带偏不调工具（3.4 E2E 实例，靠 system 指令加固才确定化）；② 工具请求白耗
> 混合检索 + qwen3-rerank API（延迟与成本）；③ RAG 请求平白携带工具 schema 干扰
> 模型决策。**定案：拆两条链 + 拆 kb-ai-agent 独立模块**（先理清后不乱，不等工具膨胀）。

### 11.5.1 模块与 Bean 形态

**kb-ai-agent**（第 9 模块，Agent 事务域容器）依赖 kb-ai-core；kb-api 依赖两者；
kb-eval 只依赖 kb-ai-core 不受影响。

| Bean | 模块 | Advisor 链（order） | tools | system 导向 |
|---|---|---|---|---|
| `ragAgentChatClient` | kb-ai-core | Audit(10)→TokenBudget(30)→RateLimit(100)→OutputGuardrail(110)→InputSanitize(300)→**SemanticInjection(320)**→Memory(400)→**QueryRouting(440)**→Trace(450)→**CacheCheck(460，条件挂载)**→**RetrievalGate(500，内包 RetrievalAugmentationAdvisor)** | 无 | 知识问基于参考资料回答 / 寒暄元问题自然直答（v2.13 双形态） |
| `toolAgentChatClient` | kb-ai-agent | Audit(10)→TokenBudget(30)→RateLimit(100)→OutputGuardrail(110)→InputSanitize(300)→**SemanticInjection(320)**→Memory(400)→ToolCallingAdvisor(1000) | `enterpriseMockTools` | 调用企业内部工具完成事务 |

> v2.13 链序变更：440 插入 QueryRoutingAdvisor（意图分类），500 位由 RetrievalGateAdvisor 承接（组合式包裹原 RetrievalAugmentationAdvisor，skipRetrieval 时旁路整套 RAG 管线，见 §11.4 v2.13 注）。
> v2.47 链序变更（安全簇⑤）：320 插入 SemanticInjectionAdvisor（L2 语义判定，12 章 §12.11）——L1 词表快筛之后、记忆之前，REGEX 可疑且干词未命中请求经备用模型二判，拒绝内容不入多轮记忆仓储。
> v2.72 链序变更（簇③ 批2）：460 插入 CacheCheckAdvisor（语义缓存，§11.9）——路由后门控前：闲聊先分流免误命中、记忆已并便单轮判定，命中旁路 Gate(500)/改写/双路检索/RRF/重排/生成全套重放缓存回答与溯源；`rag.cache.enabled=false`（缺省）时 Bean 缺位经 ObjectProvider 容忍，链形态与变更前完全一致。

**共享基座（均留 kb-ai-core）**：smartRoutingChatModel（主备容灾两链同享）、
agentChatMemory（同 sessionId 跨链历史互通——历史进 prompt 不进检索 query）、
护栏/配额 Advisor（安全与成本管控不分流）、RetrievalContext（双重角色：检索链
租户过滤/溯源载体 + 配额护栏与工具审批的身份源，**两链都必须创建传递**）。

**物理隔离收益**：rag 链零工具 schema、tool 链零检索消耗；toolContext 通道仅在
ToolChatService 组装——RagChatService 签名无 approvedToolCallId，不可能组合从
类型系统层面消除。

### 11.5.2 mode 路由协议

请求体新增 `mode` 字段（`rag`|`tool`）：**缺省 rag（兼容现状）**；大小写归一；
非法值 400 `INVALID_MODE`（协议层错误在请求处理期拒绝，不进 SSE 流）。
（实现期简化：草图的 `rag.agent.default-mode` 配置项未保留——缺省值硬编码，
避免无消费场景的配置面膨胀。）**跨链自动意图路由不实现**——仍预留 Phase 5.4
（§11.4 QueryRoutingAdvisor 锚点为正交增量：5.4 是 rag 链内部「简单查询跳过检索」
的自适应短路，与本次「跨链分流」两个维度）。v2.13 补注：rag 链内免检索短路
（5.4 收窄版）已于 2026-08-08 提前落地，见 §11.4 v2.13 注；mode 契约本身不变。

### 11.5.3 SSE/响应协议精简

| 事件 | rag 链 | tool 链 |
|---|---|---|
| TOKEN*（无名） | ✅ | ✅ |
| TOOL_CALL（命名，条件） | ❌ 零工具不产生 | ✅ 有记录才推送 |
| TRACE（命名） | ✅ | ❌ 无溯源数据不推空帧 |
| [DONE] / ERROR | ✅ | ✅ |

同步响应结构不变（answer/sessionId/toolCalls，rag 模式 toolCalls 恒空）；rag 模式
收到 approvedToolCallId 忽略 + WARN（调用方协议误用提示）。

### 11.5.4 组件迁移清单（3.19 落地形态）

| 组件 | 位置 |
|---|---|
| EnterpriseMockTools / ToolApprovalService / ToolContextKeys | kb-ai-agent `com.enterprise.kb.ai.agent.tool`（自 kb-ai-core git mv） |
| ToolAgentChatClientConfig（toolAgentChatClient + agentToolCallingAdvisor(1000)） | kb-ai-agent |
| ToolChatService（chatTool/chatStreamTool + toolContext + 确认指令） | kb-ai-agent |
| RagAgentChatClientConfig（ragAgentChatClient + agentChatMemory） | kb-ai-core（原 AgentChatClientConfig） |
| RagChatService（chatRag/chatStreamRag） | kb-ai-core（原 ChatService） |
| AgentController（mode 解析分发） | kb-api |

### 11.5.5 后续演进（不排期）

kb-ai-agent 为 Agent 事务域容器：真实 OA/ERP/数据库工具客户端（外部 SDK 依赖）、
MCP Server 宿主（5.11）、Multi-Agent Orchestrator（5.3）均落此模块；kb-ai-core
保持纯 RAG 核心不受污染。**多 ChatClient Bean 纪律**：所有注入点显式 @Qualifier
（3.2 @Primary 歧义教训）。

---

## 11.6 全链路审计日志（v2.10 新增，任务 3.12）

> **v2.10 实现期定稿（2026-08-05）**：AuditTraceAdvisor（order 10 最外层）落
> kb_audit_log。链序表「审计保持最外层以记录被拒/被限流的攻击请求」的落地机制与
> 双链路时代的数据面扩展如下。

### 11.6.1 被拒请求捕获机制

`BaseAdvisor` 默认 adviseCall/adviseStream 在内层抛错时**跳过 after()**——草图
「before+after 记录」形态无法覆盖被拒请求。定稿：覆写 adviseCall（try/catch
包裹，错误原样上抛）与 adviseStream（doOnComplete/doOnError 旁路）——内层任意
Advisor 抛错（限流 429 / 注入拦截 / 预算超额 / 工具链异常）均落审计。
status 三态：`SUCCESS` / `REJECTED`（BusinessException，errorCode 落库）/
`ERROR`（未预期异常，异常类名落库）。

### 11.6.2 双链路数据面（3.19 后扩展）

- **mode**：RagChatService/ToolChatService 经 advisor 参数（`AuditTraceAdvisor.MODE_KEY`）
  注入 rag|tool——Advisor 为共享 Bean，链归属经参数区分；
- **tool_calls**：RetrievalContext.ToolCall 列表 JSON 投影（HITL 审批状态可回溯）；
- **表结构 v2.10 扩展**：kb_audit_log 新增 mode / status / error_code / tool_calls
  四列（第七章 DDL 同步；**存量库须先执行 ALTER，ddl-auto=validate 缺列启动失败**）。

### 11.6.3 字段数据源与脱敏

| 字段 | 数据源 |
|---|---|
| query_text | Prompt 末条用户消息——order 10 先于 InputSanitize(300)，**落库前经同款 sanitize 规则脱敏**（3.5 PII 不绕过审计落库，与 Controller 归档同策） |
| rewritten_query | RewriteCapturingQueryTransformer 装饰器写回 RetrievalContext（源码核验：RetrievalAugmentationAdvisor 将 advisor 参数复制进 Query.context，装饰器经 query.context() 读同一实例）；调试台直注原始 transformer Bean 不受影响 |
| retrieved_chunks / reranked_chunks | RetrievalContext trace（bm25/vector → 原始命中；final → 重排序列），轻量投影（chunk_id/file_name/page_num/score） |
| model_name / token_usage | 响应 metadata；流式未开 include_usage 为 null（坑位⑧已知限制） |
| final_answer | 同步取响应文本；流式 doOnNext 累积（**不缓冲 token，审计不得损 TTFT**） |
| trace_id | 请求级 UUID（OTel trace 接入归 Phase 4.1）；**v2.14 起前移至 Controller 请求线程生成**，经 RetrievalContext.traceId 透传原样落库并随 DONE 帧/同步响应送达前端（反馈关联键），无 ctx 入口回落自生成 |
| feedback | **v2.14 已接线（3.17）**：反馈 API 凭 trace_id 定位本行回填 POSITIVE/NEGATIVE，同写 kb_feedback.audit_log_id（审计行缺失静默跳过，旁路容错） |

### 11.6.4 容错与性能

审计是旁路增值数据：构建/落库任何环节失败仅告警丢弃，绝不击穿问答
（ChatSessionService 归档同款哲学）；落库走 auditExecutor 虚拟线程异步，不占
响应路径延迟；RetrievalContext 为请求级共享实例，异步落库前先提取快照防竞态。
kb-eval 评估链不挂本 Advisor（评估流量不污染审计）。`rag.audit.enabled` 可关。

## 11.7 历史会话列表与恢复（v2.17 新增，3.15 清单缺口补齐）

> **v2.17（2026-08-09）**：3.1 PG 归档的设计目的之一就是「前端历史会话列表」（ChatSessionService javadoc 锚点、附录 C P1 端点预留），但 citations 列一直未填充、无读取端点、Redis 记忆 TTL 24h 过期后续聊失忆——本节记录补齐形态。

### 11.7.1 溯源归档契约（kb_message.citations 预留列启用）

归档时把本轮 TRACE 载荷写入 assistant 消息 `citations`（JSONB）：`archiveTurn` 增参
`TraceEvent + traceId`，Controller 复用 `safeBuildTrace(ctx)` 投影（与 SSE TRACE 帧**同形**：
三路 sources + chunks + docId + 分数），经 Jackson 3 `JsonMapper` 序列化；`metadata` 写
`{"traceId":...}`（反馈回填审计行凭据）。tool 链零检索、闲聊免检索直答（5.4）无溯源 → null；
序列化失败降级 null + warn（溯源是旁路增值数据，不击穿归档）。

**选型否决**：读取时反查 kb_audit_log——kb_message 无 trace_id 列，关联只能靠时间+内容
模糊匹配；audit chunks 形态 ≠ TRACE 三路形态，final 序列重建与 `[ref-N]` 对齐有风险；
审计表有脱敏/降级缺口且是运维视角表，不当产品读取事实源。

### 11.7.2 会话 API（SessionController，`/api/v1/sessions`）

| 方法 | 路径 | 语义 |
|------|------|------|
| GET | `/api/v1/sessions?page&size` | tenant+user 双过滤，updated_at 倒序（idx_user_session），size ≤100 |
| GET | `/api/v1/sessions/{id}/messages` | 归属校验 fail-closed；assistant 附 sources/traceId/反馈回显 |
| DELETE | `/api/v1/sessions/{id}` | 硬删：同事务先清 kb_feedback（message_id 外键无级联，v2.17.1）→ 删会话（kb_message 外键 CASCADE）→ memory.clear 旁路 |

- 路径前缀沿用既有扁平实现惯例（`/chat`、`/feedback` 同款）——附录 C 的
  `/api/v1/agent/sessions` 为草图锚点（同表 `/api/v1/agent/chat` 实际落地即 `/chat`），回写注记
- 归属校验：不存在/跨租户/跨用户一律 `SESSION_NOT_FOUND` 404（不泄露存在性，
  FeedbackService `MESSAGE_NOT_FOUND` 同纪律）；Controller 层身份守卫与 AgentController 同款
  （tenantId 缺失 `IDENTITY_INCOMPLETE`）
- citations 反序列化为 `TraceEvent` 后取 `sources()` 下发——前端直接作为 `Message.sources`
  消费，`[ref-N]` ↔ final 序列下标对齐契约天然保持（点击徽标弹原文零适配）；损坏 JSON →
  warn + null（降级同存量数据形态）
- 反馈回显：kb_feedback 按 `messageId IN (...) AND userId` 批量联查（upsert 语义下至多每消息一条）

### 11.7.3 过期会话续聊：记忆回填（reseedMemoryIfAbsent）

Redis 记忆 TTL 24h，久未活动会话续聊时窗口已空。chat 同步/流式两入口在 Advisor 链
执行前统一前置回填：

```
SETNX 守卫 rag:session-reseed:{sessionId}（Redisson RBucket.trySet，TTL 30s，
  仅覆盖 check-then-act 竞态窗口）→ memory.get 非空即返回（热会话零开销）
→ PG 最近 20 条（与窗口同规）倒序取出反转升序 → USER/ASSISTANT 映射为
  UserMessage/AssistantMessage（空 content/未知 role 跳过）→ memory.add
```

全程 fail-open（任何异常仅 warn，最坏=现状）；经 agentChatMemory Bean 读写，
FaultTolerantChatMemory 容错天然覆盖。回填对前端透明，无协议变化。

### 11.7.4 前端形态（SessionList.vue）

对话页内左侧可收起会话栏：标题 + 相对时间、当前会话高亮、hover 删除（二次确认）、
滚动加载更多；选中会话拉消息映射为现有 `Message[]`（sources=citations、messageId=
kb_message.id、traceId/feedback 回显）经 `openSession` 整替 store，sessionId 续用即续聊；
流式中禁切换。**归档竞态**：首轮归档为异步旁路，DONE 后延迟 1s 刷新列表防空窗。
历史消息与实时轮同一渲染链路——markdown 占位管线（v2.16）、溯源面板、原文对话框、
反馈按钮全部复用；存量消息 citations=null → 无溯源面板（降级预期）。

## 11.8 MCP Server 产品化（v2.37 新增，Phase 4 簇⑤ 4.10）

**定位**：企业知识底座对外 MCP 暴露面——Claude/Cursor/内部 Agent 多入口共享
同一知识库（v2.29 复审最大变量 N1：MCP 为 2026 Agent 互操作事实标准 +
Spring AI 2.0 GA 原生 starter + Vectara/Notion 商业先例，四条件同时成立提前落地）。

**装配形态（源码级核验证）**：
- 宿主 starter `spring-ai-starter-mcp-server-webmvc`（spring-ai BOM 2.0.0 管理）落
  kb-api——Streamable HTTP 传输，端点 `spring.ai.mcp.server.streamable-http.mcp-endpoint`
  默认 `/mcp`，**RouterFunction 形态**（SecurityConfig requestMatchers 直接拦截）；
  `type=sync` 同步执行（工具在请求线程跑，SecurityContext 可用）。
- 工具注册经 **@McpTool 注解扫描器**（McpServerAnnotationScannerAutoConfiguration
  默认开）：带 @McpTool 方法的 Bean 自动收编为 MCP 工具，零手工 specification。
  `@McpArg` 的 `required` 默认 false——必填参数须显式钉 true。
- **泄漏面核验**：ToolCallbackConverterAutoConfiguration 会自动收编容器内全部
  ToolCallback/ToolCallbackProvider Bean——项目工具链经 `.defaultTools()` 内联
  装配（EnterpriseMockTools 非 Provider Bean），容器内无此类 Bean，HITL Mock
  工具不漏进 MCP。
- 已知坑位登记：spring-ai#6465（starter 传递 Boot 依赖）经父 POM Boot BOM 导入
  钉死；MCP SDK 2.0.0（mcp-core/mcp-spring-webmvc/mcp-json-jackson3）对齐
  2025-11-25 规范；**坑位㉚（E2E 实证）**——Streamable 传输须显式钉
  `spring.ai.mcp.server.protocol: STREAMABLE`：源码核验
  EnabledStreamableServerCondition `matchIfMissing=false` 而 SSE 条件
  `matchIfMissing=true`，缺省静默装配 SSE 端点（/sse + /mcp/message）致
  /mcp 无路由 404（spring-configuration-metadata 标注默认 streamable 与
  条件装配实际行为不一致）。

**模块落位**：三件套工具 `McpKnowledgeTools` + 身份守卫 `McpIdentityGuard` 落
kb-ai-agent（CLAUDE.md「MCP 落此」定位；依赖 kb-ai-core 检索器/RagChatService +
kb-domain 仓储传递可达）；kb-ai-agent 不可反向依赖 kb-api——JWT 消费直取
SecurityContextHolder principal（同 kb-admin 纪律，不复用 JwtUtils 防成环）。

**三件套**（工具粒度对齐业界共识）：
1. `search(query)`——直调检索链（Compression 改写 → 双路召回 → RRF → qwen3-rerank，
   同 RetrievalDebugController 形态），返回 Top-K SearchHitView（chunkId/文件名/
   标题路径/页码/正文/重排分/最终序）。top-k 不开放逐请求参数——`rag.retrieval.*`
   为 kb-eval 门禁关联参数（改参须配评估纪律），MCP 面与链路配置同源。
2. `get_document(documentId)`——纯 PG 读：文档元信息 + 存活 chunk 序列
   （**软删行不返回**——MCP 消费面只读活数据；heading_path 经 metadata JSONB
   容错解析回填）；`rag.mcp.get-document.max-chunks` 默认 50 截断防工具响应爆炸。
3. `ask(question)`——经 ragAgentChatClient 全链：意图路由/护栏/配额/审计/
   多模型路由**自动复用**；每次调用独立会话 ID——`mcp-` 前缀（审计来源标记）
   + 去横线 UUID **钉死 36 字符**（E2E 实证：带横线 UUID 前缀形态 40 字符
   超 kb_audit_log.session_id VARCHAR(36) 致审计落库失败）；注入载荷 →
   PROMPT_INJECTION 经 MCP 错误帧回传（DoD 护栏验证项）。

**身份与 scope 治理（fail-closed）**：/mcp 端点 SecurityConfig authenticated
（JWT bearer，OAuth2 Resource Server 同 /api/** 链）→ McpIdentityGuard 请求线程
捕获 Jwt **立即物化 RetrievalContext 纯实例**（其后参数链传递，无 ThreadLocal
跨越异步边界）：① JWT 缺失 → IDENTITY_INCOMPLETE；② owner claim 空白 →
IDENTITY_INCOMPLETE（绝不无过滤进检索链，同 AgentController 口径）；
③ `rag.mcp.scope.required` 非空时 JWT scope 声明（Collection / 空格分隔字符串
双形态容错）须包含之 → 否则 MCP_SCOPE_DENIED（Casdoor 应用级 scope 的治理抓手；
默认空 = 仅租户纪律，兼容存量令牌）。**调用级强制**；tools/list 注册面为静态
全集（注解扫描器形态），部署级可见性经网关/客户端配置治理留档不内建。

**审计形态**：ask 落 kb_audit_log 全链路快照（AuditTraceAdvisor 链上既有，
mode=rag）；search/get_document 非对话调用，审计经 `rag.mcp.search /
get_document / ask` 指标面（AiBusinessMetrics recordMcpToolCall 收口，
零标签纪律同款 chunk.*/badcase.*）。

**错误语义**：工具方法抛 BusinessException 经注解提供器传播为 MCP 错误帧
（InvocationTargetException 解包）——MCP_DOC_NOT_FOUND（不存在与跨租户同返，
不泄露存在性）/ MCP_QUERY_EMPTY / MCP_SCOPE_DENIED / 链路既有码族
（PROMPT_INJECTION/RATE_LIMITED/TOKEN_BUDGET_EXCEEDED）。

```java
@Component
public class McpKnowledgeTools {
    @McpTool(name = "search", description = "...", annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<SearchHitView> search(@McpArg(name = "query", required = true) String query) {
        RetrievalContext ctx = identityGuard.requireIdentity();   // 请求线程 JWT → 纯实例
        // 改写 → 双路召回 → RRF → 重排（同 RetrievalDebugController 链形）
    }
    // get_document：findById 租户守卫 → 存活 chunk 序列（软删过滤 + maxChunks 截断）
    // ask：ragChatService.chatRag(question, "mcp-" + UUID, ctx) 全链复用
}
```

## 11.9 语义缓存（v2.71 新增，Phase 5 簇③ 5.6）

> **落地范围**：批1（2026-08-24）核心存取与失效（`SemanticCacheService`）+ 指标 +
> 配置族；批2（2026-08-25）`CacheCheckAdvisor` 链序接线、写入侧质量守卫、事件驱动
> 失效四处接线与观测 span（见本节「批2」段）。批3 收口：N1 供应商侧 Context Cache
> 定案 + 用户侧 E2E 验收。

**形态**：查询级语义缓存——相似问句命中后短路「检索 + 重排 + 生成」全链，重放缓存
回答与溯源（命中语义即「证据与生成已固化」）。载体 = Redis 8 内建查询引擎
（FT.* VECTOR，Redis 8 GA 起将原 RediSearch 合入开源核心，无需 redis-stack），
经项目既有 Redisson 客户端 `RSearch` 类型化 API 消费——**零新增依赖**（复审定案
否决 Spring AI `vector-store-redis` 官方模块：其底层 Jedis 与项目 Redisson 形成
双客户端；Redisson 4.x 类型化 search API 契约已源码级核验）。

**存储模型**：每租户一索引（`kb-cache-idx-{tenant}`）+ 键域 `kb:cache:{tenant}:{指纹}`
——索引域物理隔离，跨租户命中结构性不可达（与检索侧租户过滤、双向量库条件装配同
域隔离纪律）。条目 = HASH 文档：嵌入向量（HNSW / COSINE / FLOAT32 小端，维度与主
检索链路 EmbeddingModel 同源 1024）+ 问句 / 回答 / 溯源载荷（TRACE 事件 JSON）/
文档引用（TAG 逗号分隔，失效反查面）/ 写入时刻。条目键 = 问句 SHA-256 前 8 字节
十六进制——同问句幂等覆盖。编解码经自持 `CacheDocCodec`（String→UTF-8 / byte[]
透传；实证核验 `StringCodec` 对 byte[] 执行 toString、`ByteArrayCodec` 强转均不适用）。

**命中语义**：KNN top-1 + 余弦相似度 ≥ `rag.cache.similarity-threshold`（KNN 返回
余弦距离 = 1 − 相似度，距离 ≤ 1 − 阈值判命中）。阈值缺省 0.95 保守起步（行业生产
区间 0.90-0.95 上沿，调研实证）——命中率不足时经配置下调观察误命中，不做无评估
证据的调参（同 `rag.retrieval.*` 纪律）。

**失效**：双通道——① TTL 兜底（`rag.cache.ttl` 缺省 1h）；② 事件驱动按文档失效
（`invalidateByDocument`：TAG 反查引用该文档的条目全删，批2 接知识库写路径四处 +
软删）。入库门槛（批2 写入侧守卫）：仅审计三态 SUCCESS + 证据非空回答可入缓存，
与反馈导出管道（16.6.1）同款复用材料质量纪律。

**容错纪律（同限流/配额族）**：启动期能力探测（`FT._LIST`）不可用 → 整体自关 +
warn；运行期任意异常 → fail-open 直通计 miss——缓存是优化件不是防线，故障不得
传导为对话链失败。**缺省关**：`rag.cache.enabled` 缺省 false（`@ConditionalOnProperty`
整体条件装配，Bean 不注册时消费方经 `ObjectProvider` 容忍缺位），用户侧 E2E 验证后
启用收真实流量——同 eval L2 / 间接注入缺省关族纪律；kb-eval 零影响。

**指标（13.3 预留位启用）**：`rag.retrieval.cache.hit / miss / invalidated` 三计数
（零标签纪律延续），命中率 = hit/(hit+miss)，对照 08 章簇③验收（>30% 真实流量
+ 命中延迟 P95 降 >40%，基线见 18 §18.4）。

**批1 质量**：全反应器 795 单测绿（+18：命中判定/边界/确定性键/失效/探测降级/
FLOAT32 小端契约）。

**批2：链序接线（2026-08-25）**——`CacheCheckAdvisor`（order 460）挂 rag 链
路由(440)/溯源(450)之后、门控(500)之前，直实现 `CallAdvisor + StreamAdvisor`
（BaseAdvisor 模板无法表达「短路内层链」语义，同 RetrievalGateAdvisor 门控形态）。
槽位裁决：① 路由先行——CHITCHAT 置 `skipRetrieval` 的免检索直答不查缓存（闲聊
不入缓存，结构性免除误命中）；② 记忆(400)已并历史——单轮判定（prompt 内 USER
消息数 == 1）可靠，多轮上下文依赖问句不入缓存；③ 命中旁路门控/改写/双路检索/
RRF/重排/生成全套。

**批2：命中重放**——缓存回答单帧下发（流式路径单 TOKEN 帧，前端追加协议兼容）；
溯源载荷（`CacheTracePayload` 投影：source/chunks[text+score+metadata]/latencyMs，
只存 TRACE/审计消费面字段，不序列化完整 Document）反序列化经
`Document.builder()` 重建回填 `RetrievalContext.addTraceEntry`——Controller 流末
TRACE 事件、审计落库、会话归档与正常检索同形消费（[ref-N] 锚定不破；解析失败降级
空溯源，回答仍有效）。重放响应携带请求上下文（CONVERSATION_ID/RetrievalContext）
透传外层记忆/输出护栏/溯源 advisor 的 after()——缓存回答同样过输出护栏聚合后验
与审计三态（命中行落 SUCCESS + 缓存溯源，usage 空即零 token 计账，语义正确）。

**批2：写入门槛（误命中防线）**——全部满足才写入：① rag 链（结构性：只挂
ragAgentChatClient，tool/eval 链零触达；MCP ask 复用本链天然纳入）；② 单轮；
③ 非闲聊路由；④ 流正常完成（SUCCESS 操作语义：拒绝/异常走错误路径不达写入）；
⑤ final 重排证据非空（空证据拒答不缓存）。问句取 InputSanitize 掩码后形态
（存取两侧同形，缓存键面 PII 卫生）。写入复用查找期嵌入向量——每请求单次嵌入
调用零重复开销（未命中付嵌入双份：缓存查找 + 检索路内部，启用后延迟对照读批3
E2E）。trace 载荷序列化失败即放弃写入（保守：不存无溯源条目）；实际落 Redis 经
auditExecutor 虚拟线程异步（同款旁路写语义），不占响应路径延迟。

**批2：事件失效接线（四处内容变更提交点）**——经
`rag:cache:invalidate` pub/sub 频道（StringCodec + JSON 载荷
`CacheInvalidationEvent{tenantId,documentId}`，命名与装配形态对齐护栏重载频道
`rag:guardrail:reload` 先例；多实例形态全实例同步失效）：
① kb-api `DocumentService` 重入库进度回调 **COMPLETED 终态帧**——覆盖
reparse/replace/索引重建/**首次入库**（重建经 `ReindexGateway` 委派 reparse
同路径，故重建侧**不重复接线**避免双发；首次入库无存量缓存，失效空转无害）；
②③④ kb-admin `ChunkOpsService` 编辑/软删/恢复三门面（编辑=内容变更即失效；
软删=证据面收缩、恢复=证据面扩张，保守失效）。发布器/订阅器与
`SemanticCacheService` 同条件装配（缺省关零开销），发布失败仅 warn（TTL 兜底）。

**批2：观测**——`kb.cache.semantic` span（对齐 `kb.rerank` 命名）包裹嵌入+查找，
`cache.outcome` 低基数（hit/miss）+ `cache.tenant` 高基数；
`ObservationRegistry` 经 `ObjectProvider` NOOP 降级（同重排观测形态）。

**批2 质量**：全反应器 817 单测绿（+25：资格五闸/命中重放/溯源往返保真/写入门槛/
流完成与错误分支/失效频道发布订阅生命周期 + 写路径接线断言）。

**E2E 热修（v2.74，2026-08-25）**——用户侧簇③-E2E 首跑暴露并修复批1 两处缺口：
① **租户索引永不创建（坑位㉝）**——项目钉的 Redisson 4.6.1 `hasIndex` 经 Lua
包裹 `FT.INFO` 判存在，仅当错误文案匹配 "not found"/"no such index" 返回不存在，
否则原样上抛（源码级核验 `RedissonSearch#hasIndexAsync`）；而 ECS Redis Stack
（RediSearch 2.10.20）对不存在索引返回 "Unknown index name"——文案不匹配致
`ensureIndex` 在 `FT.CREATE` 前即中断，查找/写入每请求经 fail-open 直通、缓存
全程未生效（启动期 `FT._LIST` 探测通过，故障面只在逐租户惰性建索引路径）。
修复 = `indexExists` 对 `hasIndex` 异常按文案族补认「unknown index → 不存在」，
非文案异常（连接故障等）仍上抛由外层 fail-open 兜底，容错语义不变。
② **启动期注册遗漏（坑位㉞）**——`SemanticCacheProperties` 仅
`@ConfigurationProperties` 无 Bean 注册（批1 单测手工装配掩盖），
`rag.cache.enabled=true` 时构造注入失败启动即崩 → 补 `@Component`（对齐
`ParsingProperties` 先例）。
**教训登记**：条件装配 Bean 的启动期装配形态不可由单测手工装配替代核验。
质量：全反应器单测绿（+2：文案兜住建索引 / 连接故障不误建）。

**E2E 热修二（v2.75，2026-08-26）**——用户侧失效演练（命中/守卫已通过后）实证
暴露**坑位㉟：TAG 反查查询未转义**——`invalidateByDocument` 以
`@docIds:{原始 documentId}` 反查引用条目，而 RediSearch TAG 表达式内 `-` 具
否定符语法含义；文档引用为 UUID 形态（连字符必然在场）→ 词法断裂报
「Syntax error at offset N near …」，按文档失效失败回落（TTL 兜底仍生效）。
修复 = 查询侧 `escapeTagValue` 对 TAG 特殊字符集（`,.<>{}[]"':;!@#$%^&*()-+=~`）
逐字符反斜杠转义——**存储侧原值不动**（转义只作用于查询解析，匹配仍按原值），
批1 既有单测断言同步修正为转义形态。批1 契约二次实证修正（首次 = 坑位㉝/㉞
v2.74）沉淀：RediSearch 查询面仅参数化查询（KNN `$BLOB` 经 PARAMS）天然语法
安全，手写 TAG/TEXT 查询串一律经转义函数。质量：全反应器单测绿（+2：UUID
转义行为 / 转义函数契约）。

## 11.10 供应商侧 Context Cache（v2.73 新增，Phase 5 簇③ 5.6 批3 / N1）

> **范围裁决（2026-08-24 用户拍板）**：复审定案 N1 原含「DeepSeek 主链 + 百炼
> 备用/评估链」双侧；实证百炼 Context Cache 支持清单**不含 qwen3 系列**（备用
> qwen3.7-plus 与评估链 Judge/rerank 全在清单外）→ 收窄为 DeepSeek 侧定案，
> 百炼侧留口（触发条件 = 百炼支持清单纳入所用模型族）。

**官方契约（核验）**：DeepSeek 上下文缓存为**磁盘级前缀缓存，默认开启、零代码、
零参数**——与语义缓存正交（语义缓存省整次调用与延迟；前缀缓存省输入 token 计费）。
命中计费 ≈ 未命中 10%；响应 `usage.prompt_tokens = prompt_cache_hit_tokens +
prompt_cache_miss_tokens`。前缀匹配为**从首 token 起连续精确匹配**——前部任何
字节变动即从变动点起失效。最小前缀门槛：官方 64 tokens（2024 公告）；
**V4-Flash 社区实测 256 门槛（2026-05，未获官方确认）**——本链主模型
`deepseek-v4-flash` 按双门槛保守评估，精确判定经用户侧 E2E 实测。

**主链固定前缀体量实测**：单轮请求固定前缀 = `RAG_SYSTEM_PROMPT`（73 字符，
+ 系统提示金丝雀随机 token）+ `GROUNDING_PROMPT` 静态头（426 字符，`{context}`
占位符之前全部规则文本）≈ **499 字符**（中文为主）——按 DeepSeek 分词器中文
压缩率估算约 300-400 tokens，**估算越过 64/256 双门槛**；精确读数经 E2E 直发
同前缀请求的 `usage.prompt_tokens` 实测（批3 用户侧步骤）。

**收益面与结构性边界**：① **单轮为主收益面**——前缀 = system + grounding 静态
头全段；② **多轮收益收窄**——历史消息插入 system 与当前轮之间，固定前缀退化为
system 段（73 字符，估算不足双门槛）→ 多轮请求前缀缓存命中率结构性偏低，
属协议形态使然，不做无评估证据的结构调整（如提示词重排）；③ **金丝雀代价**——
系统提示金丝雀（安全簇① T5）每进程启动随机一次 → 进程生命周期内前缀稳定、
重启后缓存重建一次，安全与缓存收益权衡可接受；④ **备用链无缓存**——
SmartRoutingChatModel 转发异构备用须重建 Prompt（坑位⑭），且百炼侧不在支持
清单（范围裁决）——熔断接管窗口输入按全价计费，本就低频路径。

**遥测面裁决（零代码）**：Spring AI 标准 `Usage` 映射面（审计
`kb_audit_log.token_usage` 与 `rag.token.*` 指标数据源）**不携带**
`prompt_cache_hit_tokens/miss_tokens` 字段——应用层遥测无缓存可见性。
**不新增自定义 DTO 映射**（观测增益不抵框架内部耦合；Usage 映射属 Spring AI
演进面）。观测面 = ① 用户侧 E2E 直调对照（同固定前缀两次调用，第二次
`prompt_cache_hit_tokens > 0` 即确证生效）；② DeepSeek 开放平台控制台账单
（缓存命中分项计价）。

**前缀稳定性纪律（N1 真正的机器侧收益面）**：前缀缓存命中依赖字节级前缀稳定——
① 对话链 Prompt 单一事实源 `PromptTemplates` + Git Ops（4.8）天然契合：模板
变更走 git diff 审计，变更即缓存重建一次（预期内代价）；② 新增/调整提示文本
优先**追加于动态段**（{context}/{query} 之后或 GROUNDING 占位区），不动静态
头部；③ 工具链/分类器等旁路 Prompt（INTENT_CLASSIFIER/INJECTION_JUDGE）独立
计费面，不与主链前缀互相影响。

**与语义缓存（§11.9）正交关系**：语义缓存命中 = 整次模型调用免除（前缀缓存
自然无发生）；语义缓存未命中 = 正常调用（前缀缓存照常作用）。两者叠加口径：
命中率读数分母为全量请求，成本读数为输入 token 计费，互不干扰。

**质量**：批3 零代码变更（默认开启即生效）——全反应器 817 单测绿基线不变；
定案依据全部入档本节。用户侧 E2E（启用开关 → 相似问重放 → 命中/延迟对照
18 §18.4 → 失效演练 → N1 直调对照）登记于用户侧待执行项清单（唯一源）。
