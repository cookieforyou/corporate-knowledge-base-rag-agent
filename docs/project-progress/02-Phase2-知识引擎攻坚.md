# Phase 2：知识引擎攻坚（第 4-7 周）【攻坚期】

> 本文档为《项目阶段推进任务清单完成记录》2026-08-21 拆分子卷（仅结构调整，内容为原始记录逐字保留）；索引导航见[主文档](./项目阶段推进任务清单完成记录.md)。

**目标**：解决复杂文档解析与专有名词检索痛点

> v2 重写（2026-07-31）：检索主线基于 Spring AI 2.0 模块化 RAG（方案甲+，决策见设计文档第十章 10.0）；深度解析因 ECS 2 核无 GPU 改用 DocMind 文档解析 API（Docling 同机不可行已核验，决策见第九章 9.1）；v1 的 2.4 已在 Phase 1 完成故移除，2.11 并入 2.10/2.11；新增 2.16 评估最小集（Phase 5 前移）。**环境前置条件核验表见 08 章 Phase 2 节（E1-E6：ES ik 插件 ✅、qwen3-rerank ✅、解析 API ✅ 已决策）**。

### 任务清单（16 项）

| # | 任务 | 负责模块 | 工时估算 | 验收标准 | 完成情况 |
|---|------|---------|---------|---------|---------|
| 2.1 | 实现 SmartParsingRouter（文本密度探测 + NATIVE/DEEP/OCR 三路由） | kb-etl | 3d | 路由决策正确，parse_route 落库 | ✅ 已完成 (2026-08-02)：三路由决策（非 PDF→NATIVE / deep-by-default→DEEP / 密度<50 字符/页→OCR）+ 上传参数 parseRoute 强制指定 + 自动路由失败回落 NATIVE（显式路由失败如实上抛）；v2.1 实施注记：表格占比探测需版面分析引擎解析前不可得，改经配置开关+上传参数显式触发 DEEP，路由结构保留设计稿形态；6 个决策单测 |
| 2.2 | ParsingServiceClient 可插拔适配层 + DocMind 文档解析大模型版接入（异步轮询，RAM AccessKey 鉴权；qwen3.5-ocr 备选；不部署本地解析服务，云 OCR 降为兜底） | kb-infrastructure | 2d | 复杂表格 PDF 输出结构化 Markdown+HTML（单元格级表格结构） | ✅ 已完成 (2026-08-02/03)：docmind_api20220711 异步三件套（submitAdvance 流上传→轮询→layoutNum 分页拉取）+ 防御式多键提取 + qwen3.5-ocr 备选（PDFBox 逐页渲染→多模态 chat）。**E2E 实证修正三处**（v2.2 回写第九章）：① 表格 HTML 需提交时开启 OutputHtmlTable（须同开 LlmEnhancement），HTML 存放于表格版面块 llmResult 字段（设计稿假设的 html 键不存在）；② 正文实际字段为 markdownContent（设计稿 markdown 键不存在）；③ layouts 按页分组下传 page_number → kb_chunk.page_num |
| 2.3 | 实现 HtmlProtectingSplitter（JSoup AST + 表格/图片保护） | kb-etl | 3d | 表格 Chunk 完整不碎片 | ✅ 已完成 (2026-08-02/03)：JSoup AST 保护 `<table>`→TABLE / `<img>`→IMAGE Chunk（original_html 落 kb_chunk.original_content）+ 小表格（<30 字符）退化文本 + 无保护标签快速路径零行为变化；E2E 实证：DocMind 表格密集 PDF 35 chunks 含 16 个完整 TABLE Chunk（0 断裂）+ 页级 page_num 全量落库；5 个保护单测 |
| 2.4 | （可选）ContextualEnrichmentTransformer 上下文增强 + IMAGE Chunk vision 摘要 | kb-etl | 1.5d | 增强前后 Top-5 命中率可量化对比；图片描述可检索 | ⏸ 延期（2026-08-03 Phase 2 收尾决策）：① vision 路径当前无数据源——DocMind 图片块按设计不进正文、语料无图片密集文档，无法 E2E 验证；② Contextual 增强变更入库 embedding 形态，与 Phase 2 验收基线时点冲突（做则存量向量全量重入库、失去对照系），其「增强前后量化对比」验收更适合 Phase 5 真实语料下的独立实验；③ 设计本为「默认关、按需开」。触发条件：图片密集语料入库 / 基线暴露特定文档型检索短板 / Phase 5 语料扩容随做 |
| 2.5 | ES kb_chunks 索引（ik 双模式分词，前置 ik 插件安装）+ ETL 双写 EsIndexWriter | kb-infrastructure/kb-etl | 2d | ik 插件核验通过；入库后 ES 可检索，chunk_id 与向量库一致 | ✅ 已完成 (2026-07-31)：kb_chunks 索引启动幂等创建（ik_max_word/ik_smart）+ EsIndexWriter 批量双写/软删/级联删（幂等，失败不阻断 ETL）+ ETL INDEXING 阶段；补 spring-boot-starter-elasticsearch（E2 实证修正：原 classpath 缺 ES 自动装配模块） |
| 2.6 | 实现 ElasticsearchDocumentRetriever（ik BM25 + tenant/is_deleted 过滤） | kb-ai-core | 2d | 专有名词/标题词关键词命中验证 | ✅ 已完成 (2026-08-01)：BM25 检索路 + RetrievalContext 请求级上下文（10.2.1，非 Web 上下文降级）+ BM25 得分/排名元数据透传 + ES 字段回归 snake_case 对齐文档；单测捕获并修复 Spring AI metadata 禁 null 缺陷；端到端命中验证随簇 B（2.9 后 kb-eval 对比） |
| 2.7 | 实现 HybridDocumentRetriever（虚拟线程双路并行 + 容错降级） | kb-ai-core | 2d | 单路故障降级，总延迟 = max(双路) | ✅ 已完成 (2026-08-01)：虚拟线程正式 API 并行双路（弃 StructuredTaskScope preview，免全链路 --enable-preview 部署负担）+ 单路 5s 超时降级；4 个容错单测全绿；端到端随簇 B |
| 2.8 | 实现 RrfFusion（K=60，排名/得分/融合分元数据透传） | kb-ai-core | 1.5d | 融合结果携带完整溯源元数据 | ✅ 已完成 (2026-08-01)：RRF K=60 融合 + 双路得分/排名/融合分元数据透传 + null 安全；6 个融合数学单测全绿 |
| 2.9 | 实现 RerankDocumentPostProcessor（DashScope qwen3-rerank API + 降级） | kb-ai-core | 1.5d | 重排序后 Top-3 精度提升，API 故障不阻塞 | ✅ 已完成 (2026-08-01)：DocumentPostProcessor 挂载形态（真实包路径 rag.postretrieval.document，process() 抽象方法）+ qwen3-rerank + 故障降级 fusion_score 截断；4 个降级路径单测全绿；簇 B 四项（2.6-2.9）全部完成，进入端到端验证。**2026-08-04 E2E 修正请求/响应契约**：qwen3-rerank compatible 端点为扁平契约（query/documents/top_n 与 model 同层；results 顶层）——原实现误用 gte-rerank 旧 DashScope 原生嵌套契约（input/parameters + output.results），自上线起每次调用 400 静默降级 fusion_score 截断（降级不报错 + final trace 照常记录，潜伏至全量评估暴露）；已改扁平请求 + 双形态响应兼容解析 + top_n 按候选数收敛 + 解析失败记录原文；契约外参数清理：return_documents 移除（gte 系参数、非 qwen3-rerank 契约字段），instruct 不传（可选任务指令，默认即问答检索任务，与 RAG 场景契合，官方文档核验） |
| 2.6-2.9 簇验证 | kb-eval A/B 基线对比（DDD 文档 12 条标注语料：FACTOID×8/TABLE×2/REASONING×2） | kb-eval | 0.5d | 混合检索较单路基线有可度量收益 | ✅ 已完成 (2026-08-02)：Recall@5 0.917→1.000、MRR 0.806→0.875、Context Precision 0.806→0.875；收益集中于专有名词用例（factoid-008「大泥球 Big Ball of Mud」单路 R=0 完全落榜 → 混合经 BM25 单路命中救回），与方案甲+ 设计假设吻合；生成侧两跑持平（链路未切换，预期内）。验证期顺带修复 5 个真跑暴露缺陷：评测手动模式不生效、切分 maxNumChunks=5 误配致长文档 ETL 失败、Judge 异步 client 凭证装配、jsonschema-module-jackson 版本错配、双探针互斥致 eval.probe=vector 不可用 |
| 2.10 | 组装 RetrievalAugmentationAdvisor（查询改写/扩展 + ContextualQueryAugmenter），替代 QuestionAnswerAdvisor | kb-ai-core | 2.5d | 多轮指代消解生效，[ref-N] 标注输出 | ✅ 已完成 (2026-08-02)：改写默认开/扩展默认关（rag.retrieval.* 开关）+ Grounding 模板（[ref-N] 标注）+ order=500 + 虚拟线程执行器；实现期核验修正设计稿两处（模板补 {query}；allowEmptyContext 语义相反→改 false+专用拒绝模板）。**2026-08-09 缺陷修复（v2.15）**：默认 documentFormatter 拼接不编号致引用编号漂移（抄正文圈号 [ref-⑤]/越界 [ref-6]/错位，详见《00-每日进度状态记录》2026-08-09 状态行）——改编号化格式器 formatNumberedContext（[ref-N] 编号行锚点，与 final trace 序列对齐）+ 提示词 ASCII 引用契约 + 前端圈号归一兜底 |
| 2.11 | 实现 RetrievalTraceAdvisor（RetrievalContext 填充 + 溯源透传，Order 450） | kb-ai-core | 1.5d | [ref-N] 与 trace 下标对齐 | ✅ 已完成 (2026-08-02)：身份填充（RequestIdentityResolver 抽象修正设计稿跨模块引用）+ trace 旁路写入响应上下文；rerank 后处理器记录 source=final 最终序列（[ref-N] 锚点）；6 个新单测；2026-08-02 重构：E2E 实证请求作用域代理于 MVC 异步请求完结后不可解析（ScopeNotActiveException——流式路径租户过滤/trace 静默失效、SSE 尾帧崩溃），RetrievalContext 改每请求纯实例经 advisor 参数→Query.context 传递，删除作用域代理与线程传播脚手架（RequestIdentityResolver/ContextPropagatingTaskExecutor/VectorStoreDocumentRetriever Bean），traceEntries 改 CopyOnWriteArrayList（双路并行写入线程安全） |
| 2.12 | SSE 命名事件推送（TOKEN/TRACE/ERROR/DONE，兼容 Phase 1 协议） | kb-api | 1.5d | 流结束推送完整溯源 | ✅ 已完成 (2026-08-02)：Flux<ServerSentEvent> 请求线程订阅（SseEmitter+线程池跨线程丢作用域）；TOKEN/ERROR/DONE 保持 Phase 1 线形兼容旧前端，TRACE 命名事件流末推送双路+final 轻量溯源 |
| 2.13 | ETL 进度推送（Redis 进度键 + WebSocket + SecurityConfig `/ws/**` JWT 握手鉴权） | kb-etl/kb-api | 2d | 大文件不阻塞 + 前端进度条；WS 握手经 JWT 校验 | ✅ 已完成 (2026-08-02)：EtlProgressRedisWriter 双通道（Hash etl:progress:{docId} TTL 24h + Pub/Sub 频道）+ 阶段基线百分比；/ws/etl/progress 端点（docId 订阅分发 + JwtHandshakeInterceptor 握手鉴权）；前端上传实时进度条消费 |
| 2.14 | 检索调试 API（后端 RetrievalDebugController，10.7）+ 前端检索调试台 | kb-api/前端 | 3d | /api/v1/retrieval/search 全维度得分；前端可查看向量分/BM25分/融合分/重排分 | ✅ 已完成 (2026-08-02)：直调检索链路（不经 LLM）输出候选全维度得分（向量/BM25/融合/重排/最终排名）+ 各阶段耗时（TraceEntry 埋点 latencyMs）+ 降级状态；前端调试台得分条归一化对比 + 改写对照 + 时延四格；ETL 向量元数据补 file_name |
| 2.15 | 前端 Chunk 观测台 + 文档管理界面 | 前端 | 2d | 文档列表 + Chunk 列表可视化 | ✅ 已完成 (2026-08-02)：文档管理 API（列表/详情/Chunks/级联删除，租户校验）+ 前端四工作区产品化（Layout 壳 + Chat 溯源对话 + Documents 管理 + Debug 调试台 + Chunks 观测台 + 品牌登录），设计系统落地（theme.css） |
| 2.16 | kb-eval 最小集：Golden Dataset（50+）+ Top-K 召回/MRR + Faithfulness，验收度量 CI 化 | kb-eval | 3d | Phase 2 验收指标全部可自动度量 | ✅ 框架完成 (2026-07-31)：EvalRunner + 检索指标（Recall/MRR/Context Precision）+ 跨厂商 LLM-as-Judge（DeepSeek 被测/qwen3.7-plus 评判）+ CI 门禁 + 负向用例集 15 条 + 标注辅助工具；2026-08-01 补修：手动模式启动即跑全量评估（此前仅 ci profile 触发，README 模式②实际不生效），标注模式显式守卫不跑评估。**2026-08-03 语料扩容+全量标注完成**：4 份语料（发票手册/K8s 规范/产品目录各 3 chunk + DocMind 介绍 PDF 35 chunk 含 16 TABLE）→ finance/k8s/product/cross/docmind 5 个 QA 集 54 正向 + 负向 20（含 5 对抗性）= 74 条；逐题按内容锚点核验 54/54 命中；分布 FACTOID 59%/TABLE 22%/REASONING 13%/MULTI_DOC 6%（16.1 目标除 MULTI_DOC 外全达）；DDD 旧 corpus-qa.json 12 条随文档级联删除 chunkId 失效移除 |

### 交付物

- [x] 双链路文档解析引擎（Tika 原生 + DocMind 解析 API 深度链路，云 OCR 兜底）（2026-08-02/03：2.1-2.2 完成并 E2E，表格密集 PDF 实证）
- [x] HtmlProtectingSplitter 保护式切分器（2026-08-03：16 TABLE Chunk 完整性实证；可选 Contextual 增强即 2.4，延期，见任务行触发条件）
- [x] 模块化混合检索引擎（HybridDocumentRetriever：向量 + ik BM25 + RrfFusion + qwen3-rerank）
- [x] RetrievalAugmentationAdvisor 组装 + RetrievalTraceAdvisor 溯源透传
- [x] ETL ES 双写（EsIndexWriter）
- [x] SSE 流式对话（TOKEN + TRACE + ERROR 命名事件）
- [x] 异步 ETL 管道（Redis + WebSocket 进度推送）
- [x] 前端检索调试台 + Chunk 观测台 + 文档管理
- [x] kb-eval 最小评估基线（Golden Dataset + CI 指标）

### Phase 2 验收标准

| 指标 | 目标值 | 度量方式 |
|------|--------|---------|
| 复杂文档解析可用率（深度链路） | > 85% | 样本集测试 |
| 表格 Chunk 结构完整性 | > 90%（表格不跨 Chunk 断裂） | 含表格 PDF 样本人工抽检 |
| 混合检索 Top-5 命中率 | > 85% | 2.16 Golden Dataset 自动评估 |
| 专有名词/标题词命中率 | > 90% | 专有名词专项测试集（2.16） |
| 流式首 Token 延迟 (TTFT) | < 1.5s | 性能测试 |
| 单次检索延迟（10万级 Chunk） | < 200ms | 性能测试（双路并行，P95） |
| 负向拒绝率（知识库外问题） | 建立基线 | 2.16 Negative Rejection 用例 |

> **验收状态注记（2026-08-04 全量基线判定，Phase 2 正式收尾）**：74 条全量评估（rerank 契约修复后首次真实生效）——Top-K Recall **0.971**（门禁 >0.85 ✓）、MRR **0.910**（>0.70 ✓）、Context Precision 0.885；专有名词专项（dm API 名/产品型号/配件码/税控设备名共 13 例）全部 R=1.00（>90% ✓）；Faithfulness **4.093**（门禁 4.0 ✓，贴线——LLM Judge 噪声带内，Phase 5 校准期再收紧或加容忍度）、Response Relevancy 5.000；Negative Rejection **1.00**（20 条含 5 条对抗性诱导全部拒答，门禁 0.85 ✓）。表格结构完整性：DocMind 表格密集 PDF 16/16 TABLE Chunk 完整、0 断裂（>90% ✓）；解析可用率经 DEEP 路由端到端成功实证。低分 3 例为确定性能力边界（枚举型跨文档 cross-02 R=0.25 / 反义表述 cross-03 R=0.67 / 页级碎片 chunk dm-02 R=0.50），保留为多查询扩展与切分演进的锚点用例。**性能两项（TTFT / 10万级检索延迟）当前语料规模（44 chunk）不构成压测条件**，度量口径移交 Phase 4 性能测试阶段（第十三章可观测 + 压测基建就绪后），不阻塞 Phase 2 收尾。

### 建议实施顺序

**E1-E6 环境前置核验最先**（ik 插件/qwen3-rerank 开通等，见 08 章）；评估基线骨架紧随（2.16 骨架 → 度量一切）；检索主线按 2.5 → 2.6 → 2.7 → 2.8 → 2.9 → 2.10/2.11 → 2.12 依赖链推进；解析支线 2.1 → 2.2 → 2.3（→ 2.4）可与检索主线并行；前端 2.14/2.15 在检索调试 API 就绪后开工。

