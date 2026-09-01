# 企业知识库 RAG Agent 工作台：Spring AI 2.0 全景实现报告（v2 拆分版）

> **项目定位**：面向企业复杂文档场景的高可用、可溯源、可运维的 RAG Agent 知识库工作台
>
> **v2 修订日期**：2026-07-31 · v1 合订本（2026-07-27）归档于 [`archive/`](./archive/)（git 历史可溯）
>
> **v2.1 实现期修正（2026-08-02）**：簇 B/C 实现与 E2E 验证中对 v2 草图的源码级/实证修正，已回写各章：
> 1. **RetrievalContext 请求作用域 → 参数化传递**（10.2.1 重写）：@RequestScope 代理在 MVC 异步请求完结后不可解析，流式路径租户过滤/trace 静默失效——改每请求实例经 advisor 参数→Query.context 传递
> 2. **RetrievalTraceAdvisor 重写**（11.1.1）：v2 草图跨模块引用 kb-api JwtUtils（依赖方向不可逆）+ 作用域填充失效——瘦身为纯上下文 Map 操作，身份填充移至 Controller 请求线程
> 3. **ContextualQueryAugmenter 空证据语义**（10.6）：`allowEmptyContext=true` = 模型自由作答（与注释直觉相反），拒答需 `false` + `emptyContextPromptTemplate`（实测 Negative Rejection 0.80→1.00）
> 4. **Grounding 模板补 `{query}`**（10.6）：augment 渲染传 query+context 双参，缺 {query} 丢用户问题
> 5. **SSE 流末溯源**（11.3）：Controller 捕获纯实例（非作用域代理），供应商容错降级不击穿流
>
> **v2.2 实现期修正（2026-08-03）**：解析支线 2.1-2.3 接入与 E2E 实证修正，已回写第九章：
> 1. **DocMind 表格 HTML 获取方式**（9.1）：需提交时开启 `OutputHtmlTable`（须同开 `LlmEnhancement`），HTML 存放于表格版面块 `llmResult` 字段（实测 ``` 围栏包裹）——草图假设的 `html` 键不存在
> 2. **正文字段名 `markdownContent`**（9.1）：草图 `markdown` 键不存在，静默回退 `text` 致 Markdown 结构全失
> 3. **页级输出与页码下传**（9.1/9.2）：layouts 按页分组为每页一个 Document，`page_number` 经切分器下传落库 `kb_chunk.page_num`；文本不跨页
> 4. **embedding 单批条数硬限制**（9.3）：DashScope 单次请求 ≤20 条，VectorStore 内部 token 分批不限条数，ETL 侧固定 10 条/批分批调用
>
> **v2.3 实现期修正（2026-08-04）**：3.1 多轮记忆落地对 v2 草图的 2.0 GA 实证修正，已回写第十一章：
> 1. **Redis 会话记忆仓储形态**（11.2）：2.0 GA 为 Jedis 形态（`RedisChatMemoryConfig`），starter 坐标 `spring-ai-starter-model-chat-memory-repository-redis`、前缀 `spring.ai.chat.memory.redis.*`——草图的 RedisTemplate 构造器不存在；自动配置 jedisClient 仅支持 host/port，项目以 `ChatMemoryRedisClientConfig` 覆盖之，连接信息（含 password/database）统一取自 `spring.data.redis.*` 与 Redisson 单一来源；依赖 Redis JSON+Query Engine（Redis 8 内置）
> 2. **Agent Bean 拆分定稿**（11.2）：记忆 Advisor 缺失 CONVERSATION_ID 为 Assert 硬断言，不可挂评估共享 `chatClient`——生产对话链独立 `agentChatClient`（记忆400/溯源450/检索500），评估继续度量纯 RAG，Phase 2 基线不受影响
> 3. **记忆容错与 PG 归档**（11.2）：FaultTolerantChatMemory 装饰降级（读失败→空历史、写失败→丢弃，Redis 抖动不击穿问答）+ kb_session/kb_message 异步归档旁路（补齐 kb_feedback 外键与历史会话列表的数据缺口）
> 4. **sessionId 会话协议**（11.2）：请求体可选 sessionId（前端复用即多轮），同步响应回传；缺省后端生成一次性 ID，兼容 Phase 1 单轮前端
> 5. **自动配置条件让位陷阱（2026-08-05 E2E 追加）**（11.2）：`RedisChatMemoryAutoConfiguration#redisChatMemory` 的 `@ConditionalOnMissingBean` 检查含 ChatMemory 类型，用户记忆 Bean 致 Redis 仓储静默回退 InMemory（对话表面连贯、Redis 零痕迹、重启失忆）——RedisChatMemoryRepository 改由 `ChatMemoryRedisClientConfig` 显式装配 + `ChatMemoryRedisWiringTest` 防回归
>
> **v2.4 实现期补全（2026-08-05）**：任务 3.9+3.10 合并项 fail-closed 安全收敛与 3.5/3.6 护栏落地，已回写第十/十二章：
> 1. **fail-open 缺口收敛**（10.2.1）：Phase 2 双路租户过滤为「tenantId 存在则过滤」形态，缺失时静默跳过——两层防线定稿：① 入口身份完整性守卫（AgentController/RetrievalDebugController 校验 tenantId 非空，缺失抛 IDENTITY_INCOMPLETE；SecurityConfig 只保证已认证，owner claim 仍可能缺失）；② HybridDocumentRetriever 防御纵深（有 ctx 无租户 → 空结果零触达双路，拒答模板承接）。kb-eval 无 ctx 评估语义不变；跨租户泄露集成用例归 3.18
> 2. **护栏草稿失效 API 修正**（12.1/12.2）：`extends BaseAdvisor` → `implements`；`request.userText()` 不存在，改 `prompt().augmentUserMessage(String)`（2.0 GA 源码核验替换末条用户消息）；注入拦截改 BusinessException("PROMPT_INJECTION")；PII 正则加边界断言防长数字串误匹配；黑名单配置化 `rag.guardrail.output.blacklist`
> 3. **流式输出护栏语义修正（草稿未覆盖）**（12.2）：BaseAdvisor 默认 adviseStream 仅对 onFinishReason 末块执行 after()，已流出违规 token 无法追回——OutputGuardrailAdvisor 覆写为聚合后验（缓冲全答判定：违规→整段替换、合规→原样顺序放行），合规优先于 TTFT

> **v2.5 安全加固立项（2026-08-05）**：3.5/3.6 交付 L1 后，第十二章新增 **12.4 护栏加固路线图**（L1 缺口盘点 G1-G4：间接注入/编码绕过/PII 覆盖/命中不可观测 + S1-S9 任务清单 + 排期原则），作为后续安全任务立项依据；第八章同步修正 3.5 验收一致性（L2 POC 移交 S5、拦截率度量移交 S6）。清单**现阶段不排期**（Phase 3 主线基础功能优先），待办登记见进度文档 Phase 3 节。

> **v2.6 实现期修正（2026-08-05，3.7/3.8 落地）**：配额护栏实现期实证回写第十一/十二章：① 租户身份经 RetrievalContext 参数链（草稿 AuthAdvisor 上下文写入前提不成立）；② Redis 故障 fail-open（可用性管控不是安全边界，不击穿问答）；③ 令牌桶 setRate 覆盖式首触写入；④ 配额码 RATE_LIMITED/TOKEN_BUDGET_EXCEEDED 统一 429（流式 SSE ERROR）；⑤ Usage.getTotalTokens() 返回 Integer 可空、流式消耗未开 include_usage 暂不计账（已知限制）；⑥ TokenBudgetExceededException 拆分独立公开文件；⑦ 11 章装配草图同步为当前真实链形（7 Advisor）。

> **v2.7 实现期定稿（2026-08-05，3.2 落地）**：SmartRoutingChatModel 实用形态定稿——三级复杂度路由移交 Phase 5.4，本期落地主（DeepSeek V4）+ 备（qwen3.7-plus 百炼）熔断切换：熔断三态无锁原子实现、失败即切不丢请求、流式 onErrorResume 接管（部分 token 后中断重复为已知取舍）、chatClient/agentChatClient 统一改注路由模型（评估链同获容灾）、`rag.routing.*` 配置与单模型降级形态，详见第十一章 11.2.2。

> **v2.8 实现期定稿（2026-08-05，3.3/3.4 落地）**：Mock 工具层先行（契约对齐真实系统，读工具自动执行 + 写工具 HITL）+ ToolCallingAdvisor 自建 advisorOrder(1000)（自动注册默认序最外层致工具循环重复穿越内层链，源码实证）；HITL 复审四要素落地：approvalId Redis 账本（TTL + 一次性消费 + tenant/user 绑定防重放）、确认态经 toolContext 通道、SSE TOOL_CALL 命名事件、写操作 fail-closed（APPROVAL_STORE_UNAVAILABLE），详见第十一章 11.2.1。

> **v2.9 双链路拆分定稿（2026-08-05，任务 3.19，用户发起的架构改进）**：单链揉合 RAG 与工具调用三痛点实证后拆分——新增 **kb-ai-agent 模块**（Agent 事务域容器）+ `ragAgentChatClient`（纯检索零工具）/ `toolAgentChatClient`（纯工具零检索）双链，请求体 `mode: rag|tool` 显式分流（缺省 rag 兼容现状，自动意图路由留 5.4），共享模型/记忆/护栏配额，SSE/toolContext 按链精简；3.14「单链全挂 8 Advisor」口径随之废止，详见第十一章 §11.5 与第六章模块结构。

> **v2.10 审计落地定稿（2026-08-05，任务 3.12）**：新增第十一章 §11.6——AuditTraceAdvisor(order 10) 全链路审计：被拒请求捕获机制（覆写 adviseCall/adviseStream，草图 before+after 无法覆盖内层抛错）、双链路数据面（mode/tool_calls 经 advisor 参数与 RetrievalContext）、字段数据源与脱敏（query_text 同款 sanitize、rewritten_query 装饰器捕获、流式聚合不缓冲）、旁路容错异步落库；kb_audit_log 四列扩展（第七章 DDL 同步，存量库先 ALTER——ddl-auto=validate）；链序表全 Advisor 装配完毕（3.14 终装达成）。

> **v2.11 业务指标落地定稿（2026-08-07，任务 3.13）**：AiBusinessMetrics 落 kb-ai-core/metrics（第六章预留包位）统一注册业务指标（反馈/命中率/检索延迟/工具成功率/token 收编），ToolCall 状态常量统一至 RetrievalContext.ToolCall；SecurityConfig 放行 /actuator/prometheus 与 /actuator/metrics/**（此前被 denyAll 拦截——「Prometheus 可采集」验收的真实缺口）；草图 cache.hit/rag.llm.* 依赖未就绪暂不注册，详见第十三章 §13.3。

> **v2.13 5.4 收窄版意图分类提前落地（2026-08-08）**：rag 链内免检索短路——QueryRoutingAdvisor(440) 双层分类（正则快路纯寒暄零 LLM / 分类+改写合并单次调用，知识问零新增延迟）+ RetrievalGateAdvisor(500) 组合式门控包裹 RetrievalAugmentationAdvisor（框架类 final，草图「Advisor 自身读标记短路」形态修正）；skipRetrieval 旁路整套管线携记忆直答，fail-open 回落检索，`rag.routing.intent.enabled` 一键回退；详见第十一章 §11.4/§11.5 v2.13 注。

> **v2.14 用户反馈闭环（2026-08-08，任务 3.17）**：DONE 帧 JSON 化承载 messageId/traceId（协议修订用户拍板），两 ID 前移至 Controller 请求线程生成（messageId 归档复用保证 kb_feedback 外键可解析，traceId 经 RetrievalContext 透传审计落库）；反馈 API POST（upsert 可改评 + 期望回答 + tags，租户/用户归属经 message→session 校验 fail-closed，归档竞态短窗轮询兜底）+ GET Bad Case 查询（租户收敛 + 原始问答文本）；kb_audit_log.feedback 回填 + audit_log_id 关联，rag.feedback.like/dislike 指标接线；详见第十一章 §11.3/§11.6.3 v2.14 注。

> **v2.15 [ref-N] 引用编号缺陷修复（2026-08-09，3.17 E2E 发现）**：ContextualQueryAugmenter 默认 documentFormatter 仅换行拼接不编号（源码核验），模型引用编号无锚点——抄文档正文圈号（[ref-⑤] 前端 ASCII 正则不匹配不可点）、越界编号（Top-K=5 出现 [ref-6]）、编号错位三层漂移；修复 = 编号化格式器（每条资料前缀 [ref-N] 编号行，与 final trace 序列对齐）+ 提示词 ASCII 引用契约 + 前端圈号定点归一兜底；详见第十一章 §11.1.2 / 第十章 §10.6 v2.15 注。
>
> **v2.16 徽标内联渲染修复（2026-08-09，3.17 E2E 后续验证发现）**：前端旧版切段渲染致 [ref-N] 徽标独占一行割裂内容、邻接标点/表格行成孤儿行；改占位符单次渲染管线（[ref-N]→@@REFN@@ 透明 token、全文单次渲染、sanitize 后换回徽标），徽标内联于段落；详见第十一章 §11.1.2 v2.16 注。
>
> **v2.17 历史会话列表与恢复（2026-08-09，3.15 清单缺口补齐）**：归档时写 kb_message.citations（预留列启用，SSE TRACE 同形载荷）→ 打开历史会话即恢复溯源面板与 [ref-N] 对齐；会话三端点 `/api/v1/sessions`（tenant+user 双过滤 fail-closed，附录 C `/api/v1/agent/*` 锚点落地为扁平路径）；过期会话续聊记忆回填（chat 入口前置，PG 重建窗口 + SETNX 守卫 + fail-open）；前端对话页内可收起会话栏，历史消息复用现有渲染/溯源/反馈链路；详见第十一章 §11.7。
> **v2.17.1（2026-08-09，v2.17 E2E 修复）**：删除带反馈会话外键违例——kb_feedback.message_id 无级联，删除会话同事务先清反馈；详见第十一章 §11.7.2。
>
> **v2.18 安全加固落地（2026-08-11，优化冲刺簇② B1）**：12.4 近期四项落地其三——S1 输入归一化（kb-commons `TextSanitizer` 公共消毒组件：归一化检测视图不回写 + 分隔符容忍 PII 正则，对话/ETL 双链同源，§12.1.2）、S2 Grounding 不可信数据标记（`<untrusted_context>` 包裹 + 指令不得执行规则，§10.6）、S4 ETL 入库扫描 + PII 入库消毒（kb-etl `SanitizingTransformer`：三存储面脱敏态 + injection_hit 打标入 kb_chunk.metadata JSONB 零 schema 变更、不阻断入库，§12.5）；S3 待簇⑤（B2）。
>
> **v2.19 工程健壮性小件批（2026-08-11，优化冲刺簇③ D1+D2）**：D1 流式 token 计账——deepseek starter 无 stream_options 支持（字节码核验），主模型 `deepSeekChatModel` 改手工装配 OpenAI 兼容形态并开 include_usage（starter 经 `spring.ai.model.chat=none` 门控让位；同名让位方案被实证否决 BeanDefinitionOverrideException，DeepSeekModelOverrideWiringTest 双向钉死），流式配额账本与审计 token 列自愈（§11.2.2 v2.19 / §12.3）；D2 四件——rerank RestClient connect/read 超时（`rag.rerank.timeout-seconds`，§10.5）、HybridDocumentRetriever 执行器收编共享 Bean `hybridRetrievalExecutor`（§10.2）、ES 双写 `refresh(true)` → `wait_for`（§9.4）、EMPTY_CONTEXT_PROMPT 无参渲染回归防御（§10.6）。
> **v2.20 Judge 校准基建（2026-08-12，优化冲刺簇④ E1）**：Faithfulness 门禁由「均值 ≥ 阈值」升级为「均值 ≥ 阈值−容忍带（默认 0.05，噪声带内 WARN 不 FAIL）且单维不崩（分类均值地板默认 3.5，最小样本 3）」；`eval.run-label` 报告快照防互覆；`eval.judge-agreement-sample` 分层抽样落人工-Judge 一致率打分表（§16.4）。**定档结论（2026-08-12 复跑）**：thinking 开/关漂移 F +0.025（4.163→4.188）在噪声带内、FACTOID/REASONING 逐位重合 → 定档 thinking 关；人工-Judge 一致率 20/20 = 100%。
> **v2.21 检索质量上限杠杆（2026-08-12，优化冲刺簇④ A4 实现批）**：HtmlProtectingSplitter 六级标题栈跟踪（Markdown/HTML 双形态），chunk 注入 `heading_path` 元数据落三存储面（kb_chunk.metadata JSONB / 向量库元数据 / ES heading_path 字段，KbChunk @Transient 载体免 ALTER，§9.2）；2.4 `ContextualEnrichmentTransformer` 复活落地——消毒后入库前生成文档级语境前缀，content 存增强文本 / original_content 存原文，默认关待 kb-eval A/B 快照决策（§9.5）。重入库窗口与 A/B 对比待 E1 定档后执行。
> **v2.21 多轮指代消解增强（2026-08-12，优化冲刺簇④ A5）**：检索链 QueryTransformer 槽位由 RewriteQueryTransformer 切换为 CompressionQueryTransformer——源码核验前者默认模板不消费对话历史，路由关闭/fail-open 回落路径追问无法消解；Compression 经 Query.history() 显式消解（中文 Prompt，{history}/{query} 硬契约单测钉死），与 440 预写机制零冲突（预写时跳过，零新增 LLM 调用）（§10.6）。追问用例集 E2E 待验证。
> **v2.22 检索锚点修复与语境并发（2026-08-12，优化冲刺簇④ A4 修复批）**：① chunk ID 由随机 UUID 改确定性 nameUUID（文档名+序号+增强前原文）——全量重入库令 Golden expectedChunkIds 整体失配（a4-heading-only 复跑检索三指标全 0.000）的根治，确定性 ID 跨重入库/contextual A/B 两臂逐位复现，存量标注经 `--eval.annotate-all` 重标注表迁移（§9.3）；② 语境增强串行 LLM 调用改虚拟线程有界并发（`kb.etl.contextual.concurrency` 默认 8，保序/失败隔离不变），治理大文档 ETL 分钟级阻塞（§9.5）；③ kb-eval 增文档级兜底检索指标——Golden `expectedDocs`（文件名）× 探针命中 file_name 匹配，跨重入库/解析漂移恒稳定，chunk 级失配时的方向性读数（§16.1/§16.2 v2.21）；④ Golden 全量重标注完成——102 条迁移至确定性 ID（80 正向双层锚点 / 22 负向），圈定口径：全库 ground truth + 开放枚举题代表性锚点（§16.4 v2.21 注 5）。
> **v2.23 Contextual A/B 定案与标注补漏（2026-08-12，优化冲刺簇④ A4 收官）**：① 双臂 A/B 定案——新 Golden chain 探针 Recall@5 0.902→0.931 / MRR 0.888→0.933 / CP 0.851→0.886，靶点 dm-13 拆分表 0.000→0.667，生成侧中性（F −0.088 噪声带内）→ `kb.etl.contextual.enabled` 默认转 true（§9.5 v2.23 数据表）；② cross-08 标注补漏——售后质保期表 chunk（61c58f6c）属「持续时长」参数证据，圈定口径②细化：全库证据集 ≤ K 条时圈全不适用代表性上限（§16.4 v2.21 注 6）。
> **v2.24 护栏可观测与注入门禁（2026-08-13，优化冲刺簇⑤ B2）**：G4「拦截事件不可观测」闭环——① S3 护栏命中指标：AiBusinessMetrics 5 项 `rag.guardrail.*` 分列计数器接四护栏调用点，拒绝型审计行复用 AuditTraceAdvisor REJECTED 既有通道，非拒绝型干预（PII 掩码/输出替换）计指标不加审计标记（§12.6）；② S6 注入拦截门禁：kb-eval INJECTION 分类 + `injection-qa.json` 四类 44 条样本（Golden 102→146），evalGuardrailChatClient 确定性判定（零 Judge 零检索），门禁 ≥95% 仅对 L1 防域子集（DIRECT+ENCODING_BYPASS），JAILBREAK/MULTILINGUAL 观察集；间接注入评估口径提案移交 Phase 5/S5（§12.6/§16.2/§16.4 v2.24）。
> **v2.25 文档生命周期增量链路（2026-08-13，优化冲刺簇⑥ C1）**：① 增量重入库双端点——`POST /documents/{id}/reparse`（MinIO 原件重走 ETL，路由缺省复现原始路由）/ `POST /documents/{id}/replace`（新文件覆盖原件，路由缺省自动决策），状态守卫经 DB 级原子占用（仅 SUCCESS/FAILED 可占用为 REINDEXING，0 行 → DOC_NOT_READY 409）；② 蓝绿管线——确定性 chunk ID 令不变 chunk 三库同 ID 幂等覆写，管线统一「全量写入 → CLEANUP 阶段 diff 清理旧有新无」，重入库窗口检索不中断、失败重试幂等收敛（§9.3 v2.25）；③ kb_document.version 列（首次入库 1，重入库成功 +1）+ status 枚举增 REINDEXING（§7.1）；④ ChunkCleanupService 三库级联共享组件（文档删除/蓝绿 diff/Phase 4.5-4.6 复用）+ Chunk 软删写侧管道接通（markDeleted 零调用方历史终结，REST 门面归 4.4）（§14）；⑤ `rag.document.reindex.started/succeeded/failed` 指标 + EsIndexWriter.deleteByChunkIds（bulk，not_found 幂等）；⑥ 补零测盲区：DocumentService 删除级联/重入库守卫 + EsIndexWriter + ETL 蓝绿管线共 30 单测。
> **v2.26 簇⑥ C1 E2E 缺陷修复批（2026-08-13）**：E2E 实测（reparse 正常 / replace version 不递增 + chunk created_at 全量刷新）定位两缺陷——① replace 占用态回写：acquireForReindex 的 @Modifying 只更新 DB 不同步内存实体（clearAutomatically 已脱管），replace 后续 save 回写陈旧 SUCCESS → ETL 误判首次入库；修复 = 占用成功后同步内存态（§9.3 v2.26）；② chunk created_at merge 覆写：手工 createdAt=now 随蓝绿同 ID merge 覆盖原创建时间；修复 = KbChunk.created_at @Column(updatable=false)（§9.3 v2.26/§7.1）。E2E 核验：reparse 确定性 ID 逐位复现 7/7（本地 nameUUID 重算全匹配，同 ID 幂等覆写实证）。
> **v2.27 簇⑥ C1 收尾：删除处理期守卫（2026-08-13）**：E2E 复验四项全过（replace version 递增 + 处理期「重入库中」展示 / reparse 未变 chunk created_at 保留原值 / 处理中再发重入库 409 DOC_NOT_READY / `rag.document.reindex.*` 计数正确）——C1 闭环；收尾增项：处理期三态（UPLOADING/PARSING/REINDEXING）文档拒删 → DOC_NOT_READY 409（状态集经 `DocumentStatus.isProcessing()` domain 单一来源；SUCCESS/FAILED 放行），前端 Documents.vue 删除按钮同状态集 disable（§9.3 v2.27）。
> **v2.28 簇⑥ D3：Testcontainers 集成测试落地（2026-08-13）——簇⑥ 收官**：按 3.18 留档执行并全量回写实现差异——kb-eval 宿主 + failsafe `*IT.java`（`mvn verify`），三容器单例（pgvector/pg17 init script 直接复用 kb-domain schema.sql 自包含版 / redis-stack-server REDIS_ARGS 注密码 / minio）+ StubChatModel/StubEmbeddingModel（hashing trick 词袋向量，纯哈希桩相似度≈0 缺陷修正）+ 排除 SmartRoutingConfig 以桩重建 smartRoutingChatModel（真实路由包装器保留）；**33 用例 × 11 IT 全绿**（2 context）：租户隔离/跨租户泄露/接地+空证据拒答/注入+PII/输出黑名单/限流/Token 预算/多轮记忆/审计完整性/意图路由 L1+L2/**文档生命周期**（C1 回归保险：reparse ID 复现 + created_at 保留 + version 递增、replace 蓝绿 diff 清理、软删）；Testcontainers 1.20.1→2.0.5（docker-java API 版本与 Docker Engine 29 冲突实证）。**IT 挖出生产缺陷并修复**：PgVectorStore 默认 idType=UUID 与 kb_embeddings.id VARCHAR(36) 失配 → delete 静默失效，VectorStoreConfig 钉 idType(TEXT)（§7.2 v2.28 / §15 v2.28 / 3.18 留档实现回写节）。

本目录是设计唯一依据。v1 原为 3794 行单文件，v2 按章拆分为独立文档，并对检索架构、Spring AI API、评估体系做了基于源码级核验的修订。

## 目录导航

### 第一卷：项目全景蓝图（战略层）

| 章 | 文档 | 修订状态 |
|---|---|---|
| 第一章 | [项目背景与市场定位](./01-项目背景与市场定位.md) | v1 原文 |
| 第二章 | [技术基座与 Spring AI 2.0 能力矩阵](./02-技术基座与SpringAI能力矩阵.md) | v2 修订（Boot 基线、vectorstore auto-config 机制、百炼接入注记） |

### 第二卷：功能需求全景图（需求层）

| 章 | 文档 | 修订状态 |
|---|---|---|
| 第三章 | [功能全景与优先级矩阵](./03-功能全景与优先级矩阵.md) | v1 原文 |
| 第四章 | [子系统功能详细设计](./04-子系统功能详细设计.md) | v2 修订（检索子系统组件更新） |

### 第三卷：技术架构设计（架构层）

| 章 | 文档 | 修订状态 |
|---|---|---|
| 第五章 | [总体架构设计](./05-总体架构设计.md) | v2 修订（Advisor 链、能力层组件更新） |
| 第六章 | [Maven 多模块工程结构](./06-Maven多模块工程结构.md) | v1 原文 + v2.9（3.19：新增 kb-ai-agent 模块与依赖链，9 模块） |
| 第七章 | [数据架构设计](./07-数据架构设计.md) | v1 原文 + v2.10（kb_audit_log 四列扩展：mode/status/error_code/tool_calls）+ v2.25（簇⑥ C1：kb_document version 列 + status 枚举增 REINDEXING）+ v2.26（簇⑥ C1 E2E 修复：kb_chunk created_at ORM updatable=false）+ v2.28（簇⑥ D3：pgvector idType(TEXT) 接线修复 + schema.sql CREATE EXTENSION 自包含，§7.2）+ v2.54（**Phase 4 簇⑥ 4.11 Flyway 迁移版本化**：spring-boot-starter-flyway + flyway-database-postgresql 12.4.0（Boot 4.1 BOM；PG 数据库模块显式声明实证）+ V1__baseline_schema.sql = schema.sql 十表幂等全量快照 + baseline-on-migrate 现网库零变更登记 + 同源双写纪律（V(N+1) 先行 / schema.sql 快照同步）+ SchemaDualSourceConsistencyTest 表集守卫 + kb-eval 主配置关/IT 重开 baseline 回归，§7.6） |

### 第四卷：分阶段落地路线图（执行层）

| 章 | 文档 | 修订状态 |
|---|---|---|
| 第八章 | [五阶段实施路线图](./08-五阶段实施路线图.md) | v2 修订（**Phase 2 任务清单重写**，评估最小集前移）+ v2.5（3.5 验收一致性修正，L2 移交 12.4）+ v2.29（**Phase 4 任务清单调研实证重写**：MCP Server 提前、K8s/Milvus 集群否决、压测/可用性口径重定义、PromptTemplateManager 改 Git Ops）+ v2.30（**安全加固专项插入**：簇⑥前插入六簇专项，收尾 12.4 S5/S7/S8 + 平台层缺口 + 词表工程化，簇⑥顺延）+ v2.62（**Phase 4 全阶段收官**：簇⑦用户侧回传——Q1 新格式 E2E 通过 + Q2 文档评审通过，收口判据全达成；下一棒 Phase 5） |

### 第五卷：核心模块技术实现（实现层）

| 章 | 文档 | 修订状态 |
|---|---|---|
| 第九章 | [知识入库 ETL 管道](./09-知识入库ETL管道.md) | v2 修订（解析路由升级、ES 双写、Contextual Retrieval 可选项）+ v2.19（簇③ D2：ES 双写 refresh → wait_for，§9.4）+ v2.21（簇④ A4：heading 路径元数据 §9.2 / Contextual 增强落地 §9.5，A/B 决策未定）+ v2.22（簇④ A4 修复批：chunk ID 确定性化 §9.3 / 语境增强有界并发 §9.5）+ v2.23（簇④ A4 收官：Contextual A/B 定案默认开 §9.5）+ v2.25（簇⑥ C1：蓝绿管线 CLEANUP 阶段 + reparse/replace 增量端点 + deleteByChunkIds，§9.3/§9.4）+ v2.26（簇⑥ C1 E2E 缺陷修复批：replace 占用态回写 + created_at merge 覆写，§9.3）+ v2.27（簇⑥ C1 收尾：删除处理期守卫 + E2E 复验通过，§9.3） |
| 第十章 | [混合检索引擎](./10-混合检索引擎.md) | v2 **完全重写**（方案甲+：模块化 RAG 架构，含决策裁决记录）+ v2.4（fail-closed 安全收敛）+ v2.15（[ref-N] 引用编号缺陷修复：编号化 documentFormatter，§10.6）+ v2.18（S2 Grounding 不可信数据标记，§10.6）+ v2.19（簇③ D2：检索执行器共享 Bean §10.2 / rerank 超时 §10.5 / 空模板渲染防御 §10.6）+ v2.21（簇④ A5：改写器切 CompressionQueryTransformer 历史感知形态，§10.6）+ v2.82（辅助模型换代：图谱抽取 qwen3.7-plus→qwen3.8-flash，§10.9.3） |
| 第十一章 | [Agent 对话链路](./11-Agent对话链路.md) | v2 修订（虚构 API 全部修正为真实 API）+ v2.3（3.1 记忆形态/Bean 拆分/会话协议）+ v2.6（3.7/3.8 配额护栏：租户参数链/fail-open/429/流式 usage 限制）+ v2.7（3.2 SmartRouting 实用形态：主备熔断切换，复杂度路由移交 5.4）+ v2.8（3.3/3.4 Mock 工具层 + HITL 四要素落地）+ v2.9（3.19 双链路拆分 + kb-ai-agent 模块独立，§11.5）+ v2.10（3.12 全链路审计落地，§11.6）+ v2.13（5.4 收窄版意图分类提前落地，§11.4/§11.5）+ v2.14（3.17 反馈闭环：DONE 帧 JSON 化 + traceId 前移 + 反馈 API，§11.3/§11.6.3）+ v2.15（[ref-N] 引用编号缺陷修复：编号锚点确定化，§11.1.2）+ v2.16（徽标内联渲染修复，§11.1.2）+ v2.17（历史会话列表与恢复：citations 归档/会话 API/记忆回填，§11.7）+ v2.17.1（会话删除反馈外键修复，§11.7.2）+ v2.19（簇③ D1：主模型手工装配 OpenAI 兼容形态 + include_usage 流式计账，§11.2.2）+ v2.37（Phase 4 簇⑤：4.10 MCP Server 产品化定稿——spring-ai-starter-mcp-server-webmvc Streamable HTTP + @McpTool 注解扫描三件套（search/get_document/ask，kb-ai-agent 落位）+ McpIdentityGuard JWT 租户/scope fail-closed + 审计/护栏链复用 + rag.mcp.* 指标，§11.8；E2E 修复坑位㉚：protocol STREAMABLE 须显式钉——缺省静默装配 SSE 端点）+ v2.47（安全簇⑤：§11.5.1 链序表双链增 SemanticInjection(320)——L2 语义判定，L1 300 之后、记忆 400 之前，拒绝内容不入多轮记忆）+ v2.76（辅助模型换代：备用模型 qwen3.7-plus→qwen3.8-flash，§11.2.2） |
| 第十二章 | [安全护栏体系](./12-安全护栏体系.md) | v2 修订（API 修正 + 注入检测升级路线）+ v2.4（3.5/3.6 落地修正：implements/userText()/流式聚合后验）+ v2.5（新增 12.4 护栏加固路线图 S1-S9，立项不排期）+ v2.6（12.3 TokenBudgetAdvisor 落地修正）+ v2.7（簇② B1：S1 归一化 §12.1.2 / S4+PII 入库消毒 §12.5，12.4.3 销项）+ v2.19（簇③ D1：流式 token 计账修复，§12.3）+ v2.24（簇⑤ B2：S3 护栏命中指标 + S6 注入拦截门禁，§12.6，12.4.3 销项）+ v2.38（簇⑤ MCP E2E：注入词表中文同族变体补强 +4 干词，injection-qa 样本 44→48/DIRECT 15，裸「系统提示词」不入表控误伤面）+ v2.39（**安全加固专项立项定案**：S5 备用模型二判 fail-open 入簇⑤/S7 PII 扩容与注册表化入簇③/S8 词表运营 Git Ops 适配入簇⑥，另立词表工程/平台层加固/间接注入运行时闭环/对抗自动化四域，S9 维持不排期；红线纪律：产出物零字面载荷 + 词表样本逐条 Base64 编码存储）+ v2.40-v2.43（安全簇①词表工程落地：§12.7 结构化词表与三源合并/七分法族系/FLAG 观察语义/编码存储纪律 + T6 语料扩面 48→127 与 attackType 五类定案 + 干净集边界问法扩充 NR 0.97 门禁收口）+ v2.44（**安全簇②平台层加固**：新增 §12.9 台账——B1 CORS 白名单显式化 / B2 上传与 chat 请求体上限 413 双通道 / B3 MCP 只读工具独立配额桶与轻量审计 / B4 CSP+HSTS 响应头钉死 / B5 dependency-check+CycloneDX 供应链基线，NVD key 强制实证）+ v2.45（**安全簇③PII 扩容与注册表化**：新增 §12.10——C1 银行卡 Luhn/座机/车牌/IPv4 四类正则扩容 / C2 TextSanitizer 静态掩码退役演进 PiiRecognizer 注册表（Java 原生对齐 Presidio 语义，Spring 单 Bean 承载单一实现源，五消费方同源）/ C3 NAME/ADDRESS 登记默认关开关预留 / 输出 PII 回显 FLAG 观察接入（簇① T5 钩子闭环）/ 干净集 PII 零命中门禁）+ v2.46（**安全簇④D1 间接注入运行时扫描**：新增 §12.8——召回证据入 grounding 前同源词表检测视图逐条扫描，warn/exclude 策略双档 + 逐条警示渲染，装配位序 rerank 之前三面对齐不破，indirect.flagged/excluded 指标）**二批（D2 入库打标消费降权）**：injection_hit 经向量/ES 契约携带 + RRF 融合分衰减机制默认关（§9 定案④度量后定案）**三批（D3a 毒化语料评估机器侧）**：indirect/ 独立语料目录（golden 结构性隔离）+ 编码引用语料模型与带外导入通道 + IndirectInjectionRunner 打标自洽双标记校验与 Judge 抑制率度量（12.6 提案落地）+ v2.47（**安全簇⑤ L2 语义判定层**：新增 §12.11——E1 SemanticInjectionAdvisor(320) 可疑触发备用模型 qwen3.7-plus 二判（REGEX 命中∧干词未命中 + 跨轮拼接信号 + fail-open 全路径 + 四态指标，双链装配）/ E2 kb-eval 门禁口径演进（用户定案门禁治 L2 判别力：力判联合链 + 三分区互斥完备契约 + L1/L1+L2 双读数 + L2 防域 ≥90% 门禁；实证依据 = 自洽契约钉死观察集触发覆盖率结构性为 0，端到端联合门禁必红）；§12.1.1 L2 层落地 / 12.4 S5 销项）+ v2.48（**安全簇⑤ E2E 实证发现**：L2 触发面口径修正——自洽契约口径仅 BLOCK 档，FLAG 档 REGEX 轨实证 14/127 注入样本运行时可触发（静默区 58 条 MULTILINGUAL 为主力）；中文指令覆写族 REGEX 覆盖缺口（E2E 句式实证锚点）登记簇⑥词表运营开篇；零代码变更，§12.11 实证依据条修订）**二批（同日，E2E 通过）**：inj-jailbreak-01 FLAG→L2 BLOCK 全链实测 + 族系归一硬化（判定输出指令显式七族枚举名 + canonicalFamily 枚举校验兜底 UNCLASSIFIED，kb-ai-core 218 绿）；kb-eval L2 复跑缓做转簇⑥后 + v2.49（**安全簇⑥ F1/F2 词表动态运营**，12.4 S8 销项：GuardrailRulesRegistry 全上下文单一词表持有 + pub/sub/mtime 双触发协调器热重载免重启——五消费方 Observer 化（volatile 承接推送，构造期装载退役）+ fail-keep 保旧 + kb-eval web-none 结构隔离三层防护；kb-admin 词表查询/命中演练端点——活视图 + 元数据形态 value 不回显 + 不计指标不落审计；rag.guardrail.reload.succeeded/failed 指标 + reload 配置族 + publish_reload_signal.py 信号通道；G1 红队/G2 语料扩容转下会话；全模块 643 绿）+ v2.50（**安全簇⑥ F1/F2 E2E 通过 + 运营工具实证修正**：手改外部词表 60s mtime 轮询兜底热重载实证触发；外部 location 覆盖语义实证——外部路径须持完整词表文件（增量文件整体顶替基线，Git Ops 整文件同步纪律钉死）；publish_reload_signal.py 三通道回落——redis-py → redis-cli（which 前置探测）→ 纯标准库 RESP socket 零依赖兜底）+ v2.51（**安全簇⑥ G1/G2 机器侧**：G1 Promptfoo red-team 对抗通道落 tools/redteam/——HTTP Provider 对接 /api/v1/chat 同步端点（护栏拒绝 400 断言面）+ 生成裁判 OpenAI 兼容 env（DeepSeek 端点避思考延迟）+ 本地生成钉死 + 首跑低档限流对齐，报告经 summarize_report.py 内容盲聚合入档；G2 语料覆盖复算探针 probe_corpus_coverage.py（复用 probe_match 同源管线，BLOCK/KEYWORD/TRIGGER/SILENT 四分区运行时口径）——基线 47/13/9/58 静默区锚点与 v2.48 一致，干净集 110 全档零命中；零 Java 变更 643 绿零漂移，首跑与扩词待用户侧）+ v2.52（**词表运营形态再评估定案**：DB+前端提案 vs Git Ops——维持 Git Ops 单一事实源（§7 红线冲突面/Git 免费审计回滚/kb-eval 快照确定性/双轨合并风险/运营频率不支撑五论据）；真实痛点 = 读路径无可视化 → F2 前端第五 Tab 落地（护栏词表活视图 + 命中演练台，元数据形态 value 零回显，后端零改动）；Plan C 延后但钉死复审触发条件：多运营者/频率>10 次每周/非技术角色）+ v2.53（**词表 DB 单轨落地**：Plan C 复审触发条件成立用户确认重启，按钉死推荐落 DB 单轨（否决双轨）——GuardrailRulesSource SPI 源选择 rag.guardrail.rules.source=file\|db（缺省 file=回滚阀门，kb-eval 恒 file 锁版）；kb_guardrail_rule 唯一事实源（编码态存储 + (side,type,fingerprint) 去重 + origin/审计列，ECS 先建表）+ GuardrailRulesSeeder 首启迁移文件源有效全集（手工构造文件源，count==0 幂等）；kb-admin CRUD 运营 API POST/GET/PUT/DELETE rules（契约只收 valueB64——前端 Base64 编码后上送，编辑弹窗浏览器内解码回显用户定案；新建默认 FLAG——A4 生命周期）+ POST /reload 热更新触发（本地同步重载 + pub/sub 广播）；写后闭环 = reload（fail-keep 不阻断受理）→ 广播 → 编码 YAML 存档导出（rules.export-dir，git 归档/eval 引用）→ rag.guardrail.rule.created/updated/deleted 指标；前端第五 Tab 写路径（新增/编辑/停用/删除/手动重载，BLOCK 晋升 A4 警示）；F2 只读列表指纹契约零改动；Git Ops 带外通道保留并存；全模块 666 绿 + 前端构建绿）+ v2.76（辅助模型换代：L2 二判载体 qwen3.8-flash，§12.11） |
| 第十三章 | [可观测性体系](./13-可观测性体系.md) | v2 修订（API 修正 + Langfuse LLM 原生可观测层）+ v2.11（3.13 AiBusinessMetrics 落地定稿 + Prometheus 采集放行）+ v2.14（3.17 rag.feedback.* 指标接线）+ v2.30（Phase 4 簇①：OTel Starter/SDK OTLP 导出 + Langfuse Cloud 免费档 + AiBusinessMetrics 增 5 计数 + 13.6 告警规则落档）+ v2.31（簇① trace 碎片化定案修复：ChatClient.builder 单参 NOOP registry 根因 + 双链显式装配 + Controller Context 桥接，E2E 28 观测合树；残余 embedding/rerank 检索层 span 留簇②/⑥）+ v2.32（簇② 面板与统计：13.4 四面板定稿 + 无指标支撑面板不设口径 + rag.ttft 指标 + 13.7 资产与本地核验形态；trace 残余修复定案——双执行器传播包裹 embedding 合树，rerank 核验不产 observation，流式主生成 POST 留簇⑥）+ v2.56（**Phase 4 簇⑥ 批2 监控栈 ECS 生产化**：§13.8 新增——四服务 restart/healthcheck/限额/日志轮转 + Grafana 口令 infra/.env 注入 :? 守卫 + node_exporter v1.9.1 入栈 + kb-rag-host 告警组激活（11→13 条）+ Jaeger scratch 基座 healthcheck 不可行实证；§13.6 自检矩阵定案 = 6 真触发演练 + 7 promtool 合成单测 alert-selfcheck/）+ v2.58（**Phase 4 簇⑥ 批4 trace 残余清偿 + 双供应商 SLA 监控**：流式主生成 POST 独立 trace 源码级定案修复——根因 chat_model 流式观测仅经 Reactor Context 传播 ∩ OkHttp 拦截器按线程 TL 寻父 ∩ Flux.create 消费者不恢复上下文，修复 = SmartRoutingChatModel.stream 订阅期父观测作用域注入（openParentScopeOnSubscribe），合树 E2E 裁决留用户侧；AiBusinessMetrics +3 SLA 计数 rag.routing.circuit.opened/half-opened + fallback.invoked + kb-rag-supplier-sla.json 第五面板 + KbPrimaryModelDegraded 告警（13→14 条，合成单测 7→8）；ECS 安全组源址限制说明交付（12.9 B4 清偿，用户侧 SG1）） |
| 第十四章 | [知识库运维](./14-知识库运维.md) | v1 原文 + v2.25（簇⑥ C1：ChunkCleanupService 三库级联共享组件 + Chunk 软删写侧管道接通）+ v2.33（Phase 4 簇③：4.4 Chunk CRUD 运维门面定稿（同源消毒 + 重嵌入 delete→add 两步实证）+ 4.5 索引重建编排定稿（ReindexGateway 依赖倒置 + 滑动窗口 + ES 孤儿清扫漂移收敛 + 10 万 chunk 时延外推论证）+ 草图三处实证修正，§14.1/§14.2）+ v2.34（Phase 4 簇④：4.7 Bad Case 运营闭环定稿（审计多选项查询 + 根因四分类标注 + Golden 回灌 Git Ops 通道 + rag.badcase.* 指标）+ 4.9 前端运维中心三 Tab，§14.4）+ v2.35（簇④ E2E 缺陷修复批：审计/反馈多选项查询 @Query 可选参数 PG 预编译类型推断缺陷 → kb-domain spec/ Specification 动态谓词修正 + kb-eval AdminQueryIT ≥6 次执行回归；Chunk 运维前端落地（4.9 扩展，补齐簇③ 运维操作面缺口，运维中心第四 Tab），§14.4）+ v2.36（重建任务表迁 Redis：RebuildTaskStore 抽象 + RAtomicLong 原子计数 + 租户域索引/FIFO 淘汰/TTL，重启保留与跨实例轮询，v2.33 全局任务列表跨租户缺口消除；fail-closed/fail-open 语义分层 + 存储不可用类 503，§14.2） |

### 第六卷：工程质量保障（质量层）

| 章 | 文档 | 修订状态 |
|---|---|---|
| 第十五章 | [测试策略](./15-测试策略.md) | v1 原文 + v2.28（簇⑥ D3：Testcontainers 集成测试落地定稿——kb-eval 宿主 + 三容器单例 + 模型桩 + 33 用例清单，15.2/15.3 草图被实现取代）+ v2.59（**Phase 4 簇⑥ 批5 Gatling 压测落地**：新增 §15.4——kb-loadtest 独立模块（gatling-charts-highcharts 3.15.1 Java DSL，gatling:test 显式触发）四场景（检索真压 P95<500ms / 生成桩压 50 并发 / 真实 LLM 小样本 TTFT·TPOT 计费敏感缺省关 / 20 并发 SSE 长会话）+ 内置 OpenAI 兼容生成桩 StubChatServer（纯 JDK 零依赖）+ SSE 协议对齐源码级定案（ServerSentEvent.asJsonString 消息形态 + jmesPath data.* 匹配 + [DONE] 原文扫描）+ SmokeProbe/StubEchoProbe 探针；ECS 执行与报告回填留用户侧清单 LT1，验收基线 18 §18.4） |
| 第十六章 | [AI 评估体系](./16-AI评估体系.md) | v2 修订（指标集扩充、CI 门禁、**阶段前移至 Phase 2**）+ v2.20（簇④ E1：Faithfulness 容忍策略 + 校准复跑快照 + 人工-Judge 一致率抽样，§16.4）+ v2.21（簇④ A4 修复批：双层检索度量——chunk 级确定性 ID + 文档级 expectedDocs 兜底 + 全量重标注通道与完成口径，§16.1/§16.2）+ v2.24（簇⑤ B2 S6：INJECTION 分类 + 注入拦截门禁，§16.2/§16.4）+ v2.25（安全加固专项收口回写：三分区互斥完备契约 + L1+L2 联合力判链与双读数 + 注入语料 48→127/Golden 237/干净集 BLOCK+FLAG 全档零命中口径 + 间接注入抑制率口径，§16.2/§16.4）+ v2.90（辅助模型换代：Judge 基座 qwen3.8-flash——缺省对齐执行事实，门禁基线无需复跑定档） |
| 第十七章 | [部署与运维](./17-部署与运维.md) | v1 原文 + v2.44（安全簇②：§17.3 平台层安全配置落档——实际部署形态 CORS/上传上限/chat 请求体/响应头/actuator 面/MCP 配额/供应链基线）+ v2.55（**Phase 4 簇⑥ 4.11 容器化与应用编排**：新增 §17.4——Dockerfile（宿主构建 jar + CDS 动态消费入口）+ docker-compose.app.yml（分层编排不纳管存量、禁 latest、healthcheck/日志轮转/限额）+ .env.example Secrets 模板 + kb-api.service 开机自启 + build-image.sh；ECS 形态定案 PG 原生 + 存量 compose 经 host.docker.internal 连接；AppCDS 部署侧训练形态，GraalVM/CRaC 否决依据沿用 N5）+ v2.57（**Phase 4 簇⑥ 批3 灾备最小集**：新增 §17.5——pg-backup.sh（pg_dump -Fc → 本地 + MinIO kb-backups 双副本，--md5 校验 + flock 锁 + 7 天保留 + cron 每日 03:07）+ pg-restore-drill.sh（独立演练库 + 表集/行数 MATCH/LAG/FAIL 三态判定，--strict/--from-minio/--api-check）+ 99.5% 兜底自检清单（docs/reports/，10 项，4.11 验收件）+ .env.example 备份段；最小集边界 = PG 唯一事实源，ES/Milvus 经簇③重建通道再生；实证口径：.env 未引号多词值 → 逐项 grep 解析不 source / 转储含 CREATE EXTENSION 权限预案 / psql 钉 ON_ERROR_STOP）+ v2.58（簇⑥ 批4：§17.3 actuator 行源址限制清偿——安全组规则说明交付，用户侧 SG1） |
| 第十八章 | [交付验收标准](./18-交付验收标准.md) | v1 原文 + v1.1（安全加固专项收口对齐：项 9 注入拦截率钉死防域子集语义（L1 防域 ≥95% + L1+L2 联合双读数 L2 防域 ≥90%）+ 项 10 PII 覆盖类型数更新为七类识别器注册表）+ v2.59（**Phase 4 簇⑥ 批5**：新增 §18.4 压测验收基线——4.12 新口径四场景阈值（检索 P95<500ms / 桩压 50 并发零失败 / TTFT P95<2s + TPOT<100ms / 20 SSE 零中断），ECS 实测列待回填（清单 LT1）；18.2 v1 草图口径（100 QPS/10 万 chunk）标注非 ECS 现实规模） |

### 附录

| 文档 | 修订状态 |
|---|---|
| [附录 A-E](./19-附录.md)（依赖清单 / 配置模板 / API 清单 / 避坑指南 / 实证坑位台账） | v2 修订（新增反模式 21-23）+ v2.1（安全专项收口批：新增附录 E 实证坑位台账 ①-㉜ 自 CLAUDE.md 迁入） |

## v2 修订摘要

### 核心决策：检索架构「方案甲+」（2026-07-31 源码级核验后裁决）

v1 设计为「ES BM25 + Milvus 向量 + 手搓并行管道 + 自研 RRF」。经对 Spring AI 2.0.0 GA 源码与 Milvus 2.6 能力的双路核验，裁决如下：

- ❌ **Milvus 原生单引擎方案否决**：`MilvusVectorStore` 源码锁死 4 字段 schema（无 sparse/BM25 Function）、单路 dense 搜索、零扩展点；Spring AI 全仓无 sparse embedding 抽象；且 Milvus jieba 中文分词存在已知质量问题（milvus-io/milvus#36743），对"中文专有名词命中"核心痛点是致命风险。12 个月后可重估。
- ✅ **采纳方案甲+**：保留 ES ik BM25（中文质量 + 已部署 + 高亮/DSL），但构建于 Spring AI 2.0 **模块化 RAG** 之上而非手搓：`RetrievalAugmentationAdvisor` + 自定义 `HybridDocumentRetriever`（双路虚拟线程并行 + `RrfFusion`）+ 框架内置查询改写/扩展 + `qwen3-rerank` 重排序 + `ContextualQueryAugmenter` 证据注入。详见[第十章](./10-混合检索引擎.md)。

### 其他修订

1. **虚构 API 清零**：v1 示例代码中 8 处 Spring AI API 不存在（`ChatClientRequest.from()`、`ToolContext.requestApproval()`、`RedisChatMemory`、`ToolRegistry.merge()`、`spring.ai.vectorstore.type=custom`、指标名 `.duration`、`response.response()`、MCP SSE 传输），已在第九~十三章及附录全部修正为 2.0.0 GA 真实 API。
2. **评估体系前移**：v1 将评估全部置于 Phase 5，与 Phase 2 验收标准（命中率 > 85%）自相矛盾。v2 将 kb-eval 最小集（Golden Dataset + Top-K 召回/MRR + Faithfulness）前移至 Phase 2，并扩充指标集（+Negative Rejection、Hallucination Rate、Noise Robustness、Citation Attribution）。详见[第十六章](./16-AI评估体系.md)。
3. **解析路由升级**：深度解析链路调整为 API 化解析——**阿里云文档智能 DocMind 大模型版**为主（ECS 2 核无 GPU 资源约束定案；Docling 同机部署经核验不可行：内存/速度双否决），qwen3.5-ocr 备选，云 OCR 兜底扫描件。详见[第九章](./09-知识入库ETL管道.md) 9.1 决策注记。
4. **可观测双层化**：Grafana（基础设施层）+ Langfuse（LLM 原生层，Spring AI 官方 OTel 集成）。详见[第十三章](./13-可观测性体系.md)。
5. **重排序选型**：BGE 本地/Cohere → DashScope qwen3-rerank API（与 Embedding 同生态、免 GPU）。

### 未修订部分

Phase 3-5 章节内容（第十二、十四章除外）保持 v1 原貌，待 Phase 2 收尾时按同样标准复审——远期设计不宜过早细化。路线图总体五阶段排期不变。

## 配套文档

- [项目阶段推进任务清单完成记录](../project-progress/项目阶段推进任务清单完成记录.md) — 进度追踪（Phase 1 已完成，Phase 2 任务清单已与 v2 对齐）
- 项目根 `CLAUDE.md` — 工程约定与当前实现要点
- v1 合订本：[archive/](./archive/) — 修订前完整原文
