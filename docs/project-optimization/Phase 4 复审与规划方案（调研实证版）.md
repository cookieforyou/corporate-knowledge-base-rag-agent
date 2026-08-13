# Phase 4 复审与规划方案（调研实证版）

> **性质**：Phase 4 规划提案（复审第八章原清单 + 2026-08-13 四路网络调研实证），经用户定案后回写第八章，不代改
> **基准文档**：[第八章 五阶段实施路线图](../project-implement/08-五阶段实施路线图.md) · [Phase 1-3 复盘报告](./Phase%201-3%20复盘：待优化点与可落地优化方案.md) · 设计文档 v2.28
> **规划日期**：2026-08-13（优化冲刺六簇收官当日）
> **信息源**：四路并行网络调研（LLM 可观测与在线评估 / 开源 RAG 平台产品化对标 / MCP 与 Agentic 生态 / 受限资源部署与压测实践，来源清单见附录 A）

---

## 一、复审背景

优化冲刺六簇收官后，Phase 4 立项输入齐备。原第八章 Phase 4 清单（14 项）成稿于 v1 设计期（2026-07 前），其间发生三类变化，需要逐条复审：

1. **项目自身演进**：优化冲刺已提前消化部分 Phase 4 铺垫（C1 增量链路为 4.4-4.6 铺好 ChunkCleanupService 基座、软删管道已通只欠门面、D3 集成测试网就位、护栏指标/注入门禁已闭环）；
2. **约束画像固化**：单机 2 核无 GPU ECS、单开发者、全 SaaS LLM API、6 文档 168 chunk 语料——清单中 K8s 多副本、Milvus 分布式集群等项与约束直接冲突；
3. **行业格局变化**：MCP 成为 Agent 互操作事实标准（2026-07-28 规范 stateless 重构）、OTel GenAI 语义约定仍 experimental、LLM 应用压测指标体系从 QPS 转向 TTFT/TPOT、开源 RAG 平台产品化能力横评有了明确标配清单。

**复审方法**：原清单 14 项逐一给出「保留 / 调整 / 否决」裁决（带调研依据）；验收标准逐条复核；新增候选项按（价值 × 确定性 ÷ 成本）排序；最后重组为成簇推进计划。

---

## 二、原清单 14 项逐条复审

| # | 原任务 | 裁决 | 依据与调整形态 |
|---|---|---|---|
| 4.1 | OTel Java Agent 自动埋点 | **调整（方案修正）** | 调研实证：**OTel Java Agent 对 Spring AI 无 instrumentation 支持**，Spring AI 走 Micrometer Observation 自有链路。正确形态 = 保留 Micrometer Observation（已有）+ OTel SDK 将 trace 经 OTLP 导出至 LLM 观测平台；`gen_ai.*` 属性仍 experimental（13.1 v2 注的间接引用策略维持）。**「Trace 覆盖率 100%」验收修正**：业界生产实践为采样（10-20%）+ 错误/低分反馈请求全量上报；项目流量小可暂全量，但口径改为「可配置采样率 + 错误全量」 |
| 4.2 | Prometheus 采集 + 告警规则 | **保留 + 扩展** | 采集端 Phase 3 已就绪（端点放行 + 17 项 rag.* 指标）。补告警规则两层：① 基础设施（磁盘/内存/JVM Heap/重启）；② LLM 特有（反馈比跌破阈值、空检索率突增、拒答率突增、Token 日耗环比突增、供应商错误率） |
| 4.3 | Grafana 4 个 Dashboard | **保留** | 13.4 四面板设计与业界共识基本吻合，微调：LLM 面板补 TTFT 分位/路由决策/护栏命中（`rag.guardrail.*` 已注册，数据源现成）；业务面板补 Bad Case 队列与标注分布 |
| 4.4 | Chunk CRUD 运维 API | **保留** | 对标结论：**chunk 级查看与编辑是全平台标配**（Dify/RAGFlow/FastGPT/MaxKB 均支持）；项目 C1 已通软删管道（只欠 REST 门面），编辑→异步重嵌入、删除、恢复三动作落 `ChunkAdminController`（14.1 草图为实现锚点，API 形态需源码级核验后落地） |
| 4.5 | 文档级物理删除 + 级联 | **降级为验证项** | C1 已将 DocumentService.delete 级联委派 ChunkCleanupService（三库级联含 ES 孤儿扫尾）——本体已完成，本期只补运维视角 E2E 验证与 admin 门面整合，不单独立项 |
| 4.6 | 索引全量/增量重建 API | **保留** | 三库漂移唯一修复兜底（ES 写入本就「失败不阻断」）；附带价值：**ES 动态 mapping 对齐窗口**（heading_path 字段完全对齐随重建执行，簇④ A4 遗留）。复用 C1 蓝绿管线与 ChunkCleanupService |
| 4.7 | PromptTemplateManager 自建 | **否决自建，改 Git Ops** | 调研结论明确：模板 <20 个 + 单开发者，自建 DB+Redis+版本管理是过度设计。业界三流派（专业平台/Git Ops/轻量自建）中本项目取 **Git Ops**：Prompt 抽为外部化配置（yml/常量类收编，git log = 版本历史）；未来如需热更/A/B 再挂 Langfuse Prompt Management。**注意**：第 18 章验收「Prompt 外部化配置率 100%」仍成立，Git Ops 形态满足 |
| 4.8 | 问答日志查询 + Bad Case 面板 | **保留 + 升级** | 审计落库与 Bad Case GET 端点 3.17 已有；本期升级为**完整闭环工作流**——查询过滤（时间/用户/会话/反馈）→ 根因标注（检索未命中/改写漂移/生成幻觉/解析不足）→ 一键回灌 Golden Set → CI 重跑。调研证实 **bad case 标注工作流是全部开源平台的空白区**，项目反馈底座齐备，这是差异化机会点 |
| 4.9 | 解析状态 + 知识库统计 API | **保留** | 轻量；对标显示各平台统计深度普遍有限，项目可做到文档/chunk/版本/解析路由分布的细粒度统计 |
| 4.10 | Kubernetes 部署 | **否决，替换为 Compose 生产化** | 调研结论：单开发者、单机、~10 容器不满足任何 K8s 触发条件（团队 ≥10 人/服务 ≥20/节点 ≥5/弹性伸缩需求）；17 章「3 副本 × 2Gi + HPA 10 副本」与 2 核 ECS 物理冲突。替换为 **Docker Compose 生产加固**：healthcheck、restart 策略、日志轮转、镜像版本化（禁 latest）、PG 备份 cron、systemd watchdog。触发条件留档：多节点/多团队协作时重估 K3s/K8s |
| 4.11 | Milvus 分布式集群 | **否决** | 官方口径 Standalone 支撑 ≤1 亿向量；分布式最小资源 40C/120G，超出 ECS 两个数量级；Milvus 2.6 Woodpecker WAL 进一步降低 Standalone 运维复杂度。项目 168 → 可预见 10 万 chunk 是极轻负载。**维持单机 Standalone，不排期，重估触发：向量规模逼近亿级或多节点部署** |
| 4.12 | 压力测试（100 QPS 目标） | **重定义指标 + 换工具** | 调研结论：**QPS 对 LLM 应用已不是合理验收口径**（流式响应时长差异 50 倍，QPS 无意义）。新口径：检索链路真压（双路+重排 P95 <500ms）+ 生成链路桩压（应用层并发能力）+ 小样本真实 LLM 采样（TTFT P95 <2s、TPOT <100ms）+ 20 并发流式会话稳定。**工具选 Gatling**（SSE 一等支持 + 2026 官方 LLM 压测指南，Java 生态亲和）；k6 备选（需 xk6-sse 扩展） |
| 4.13 | 前端运维审计看板 | **保留** | 与 4.8/4.9 同簇推进：仪表盘（统计）+ 日志查询 + Bad Case 标注界面 |
| 4.14 | 文档三件套 | **保留** | 运维手册 + API 文档 + 用户手册；API 文档随 4.4/4.6/4.8 新端点同步产出 |

**复审小结**：14 项中保留 8 项（含 2 项升级）、调整 2 项（4.1 方案修正、4.12 指标重定义）、降级 1 项（4.5 并入验证）、否决 3 项（4.7 自建改 Git Ops、4.10 K8s、4.11 Milvus 集群）。

---

## 三、验收标准复审（第八章 + 第十八章联动）

| 原标准 | 裁决 | 新口径 |
|---|---|---|
| OTel Trace 覆盖率 100% | 修正 | 采样率可配置（当前流量全量）+ 错误/低分反馈请求全量上报；trace 在观测平台可查 LLM 调用树 |
| 生产可用性 >99.9% | 修正 | 单机数学上不可行（99.9% = 年宕机 ≤8.76h，单次 ECS 故障+恢复即可能超限）；**务实目标 99.5%**（年宕机 ≤43.8h）+ 灾备最小集兜底（备份/自动重启/健康检查/镜像版本化） |
| 100 QPS 下 P95 <3s | 修正 | 检索链路 P95 <500ms（真压）；TTFT P95 <2s（小样本真实 LLM）；20 并发流式会话稳定；QPS 口径废止留档 |
| 10 万 Chunk 索引重建 <30min | 保留 | 与 4.6 验收对齐（当前 168 chunk 外推验证 + 合成数据集抽测） |
| Bad Case 可回溯率 100% | 保留 | 审计全链路已达标，本期补标注闭环 |
| 第 18 章：Prompt 外部化配置率 100% | 保留（形态调整） | Git Ops 形态满足（4.7 裁决） |
| 第 18 章：DB 迁移脚本版本化（Flyway/Liquibase）100% | **纳入本期** | 现状 schema.sql + ECS 手工 ALTER（ddl-auto=validate），是生产化真实缺口——本期引入 Flyway，存量 schema 转基线迁移脚本（归生产加固簇） |

---

## 四、新增项提案（调研实证驱动）

### N1. MCP Server 宿主：从 Phase 5.11 提前至 Phase 4 —— **本规划最大变量**

四路条件同时成立，构成提前落地的充分依据：

1. **生态成熟**：MCP 是 2026 年 Agent ↔ 工具互操作事实标准（OpenAI/Google/Microsoft/Anthropic 全采纳）；2026-07-28 规范完成 stateless core 重构（Streamable HTTP + OAuth 2.1 + elicitation），进入生产级成熟期；
2. **框架就绪**：Spring AI 2.0.0 GA 原生 `spring-ai-starter-mcp-server-webmvc`，`@McpTool` 注解已毕业入 core——与项目栈完全匹配，与 `EnterpriseMockTools` 的 `@Tool` 风格同源，迁移成本低；
3. **模式验证**：「知识库即 MCP Server」商业先例充分（Vectara/Notion/Confluence/Oracle OIC），工具粒度共识为 `search(query, topK)` + `get_document(docId)` + `ask(question)` 三件套；
4. **战略契合**：项目定位「企业知识底座」——暴露为 MCP Server 后，Claude/Cursor/内部 Agent 多入口共享同一知识底座，这正是产品化的核心价值点；同时为真实 OA/ERP 工具接入（`@Tool` + `@McpTool` 双注解）与 5.4-A 跨链路由铺好地基。

**成本**：接入 ~1-2d + OAuth 鉴权治理 ~2-3d + 审计/限流复用 ~1d ≈ **1 周**。
**前置安全要求（不可省）**：美国 DoD 2026-06 报告定性 MCP 工具投毒/语义注入为系统性威胁——Server 必须做输入 schema 校验、工具可见性 scope 控制、调用审计（项目审计链现成复用）、Casdoor JWT → tenantId 映射复用 JwtUtils。
**已知坑位（调研登记）**：Spring AI 2.0.0 starter 传递拉取 Boot 4.1.0 依赖需显式 pin（spring-ai#6465）；MCP SDK 对齐 2025-11-25 规范（未跟进 2026-07-28 stateless，影响有限）；`validateToolInputs=true` 默认强校验。

### N2. Bad Case 运营闭环（见 4.8 升级项）

业界标准流程落地：反馈/拒答/低分 → 每日 bad case 队列（自动）→ 根因标注（人工，四分类）→ 每周回灌 Golden Set → CI 重跑防回归。项目独有底座：kb_audit_log 全链路 + kb_feedback 期望回答字段 + kb-eval CI 门禁，只差标注界面与回灌通道。

### N3. 生产灾备最小集 + Flyway（见验收标准复审）

备份 cron（pg_dump → MinIO/OSS）、配置即代码、镜像版本化、healthcheck、自动重启、Flyway 迁移版本化。单机场景投入产出比最高的生产加固组合。

### N4. PPT/Excel 格式支持（复盘报告候选项采纳）

上传白名单 PDF/DOCX/MD/TXT/HTML 缺口（企业知识库常见格式）；Tika 解析能力已覆盖，主要是白名单 + E2E 验证，归语料扩容伴生小件。

### N5. AppCDS 启动优化（顺带小件）

调研结论：GraalVM Native/CRaC 对 Spring AI 场景兼容性风险高、投入大，否决；**AppCDS 是零代码改动、构建时加参数的免费优化**，随生产加固簇顺带执行。

### 明确不新增（触发条件驱动，登记在案）

| 项 | 不排期理由 | 重估触发 |
|---|---|---|
| RBAC Metadata Filtering（3.11） | 复盘定案维持：待真实组织结构需求；调研已备好形态（chunk 入库打部门/密级标签 + 检索注入过滤，3-5d 量级） | 真实部门/角色权限需求提出 |
| 飞书/钉钉连接器 | 调研证实 2026 原生连接器生态仍不成熟（无完整开源同步方案），手动上传最务实 | 语料规模/同步频率痛点出现 |
| 真实 OA/ERP 工具接入 | 依赖真实系统清单；MCP 底座（N1）就绪后接入成本显著降低 | 真实系统清单确定 |
| 5.4-A 跨链 mode=auto | 既定：真实工具伴生 | 同上 |
| 在线抽样 LLM-as-judge | 流量太小，采样在线评估无统计意义；离线 kb-eval 已覆盖 | 日对话量破百 |
| 第三 LLM 供应商备援 | 主备熔断已是小团队最优形态；先积累双供应商 SLA 实证数据再定 | DeepSeek/百炼 SLA 实证劣化 |

---

## 五、重组后的 Phase 4 任务清单与成簇推进计划

> 沿用优化冲刺「2-3 关联点成簇」纪律：每簇 = 实现 → 验证 → 文档回写 → 提交 闭环；验证不过不进下一簇。

### 5.1 簇构成

| 顺序 | 簇 | 构成 | 关联逻辑 | 估算 | 验证通道 |
|---|---|---|---|---|---|
| ① | 观测地基 | 4.1 修正版（Micrometer Observation → OTLP 导出 + 采样配置）+ LLM 观测平台接入（Langfuse/Phoenix 选型待定案）+ 4.2 告警规则 | trace 先通，平台先见数据，告警挂在已通数据上 | 3-4d | LLM 调用树在平台可见 + 告警自检触发 |
| ② | 面板与统计 | 4.3 Grafana 四面板 + 4.9 统计 API | 面板数据源 = 已注册指标 + 新统计 API | 2-3d | 面板自采核验（curl + 截图） |
| ③ | Chunk 运维与索引重建 | 4.4 Chunk CRUD 门面 + 4.6 重建 API + 4.5 删除级联验证 + ES mapping 对齐窗口 | 同域（kb-admin 模块首建）+ 共享 ChunkCleanupService/蓝绿管线 | 4-5d | E2E：编辑后检索命中新内容 / 软删恢复 / 重建漂移收敛 |
| ④ | Bad Case 运营闭环 | 4.8 日志查询 + 根因标注 + Golden Set 回灌通道 + 4.13 前端运维看板 | 一条工作流串起后端查询→标注→回灌→前端 | 5-6d | 一轮真实闭环：标注 N 条 → 回灌 → CI 复跑 |
| ⑤ | MCP Server 产品化（N1） | `@McpTool` 三件套 + Casdoor JWT 鉴权 + scope 治理 + 审计/限流复用 | 单域闭环；安全治理内置不单独成簇 | 5-7d | MCP Inspector/Claude Desktop 真实调用 + 跨租户隔离验证 + 注入载荷经 MCP 通道护栏验证 |
| ⑥ | 生产加固与压测 | Compose 生产化 + Flyway + 备份灾备 + AppCDS + Gatling 压测（新指标口径）+ 双供应商 SLA 监控 | 部署/灾备/压测同属生产化域 | 4-6d | 压测报告（新口径）+ 备份恢复演练 + 99.5% 兜底自检 |
| ⑦ | 文档与格式收尾 | 4.14 文档三件套 + N4 PPT/Excel 白名单 | 收尾小件批 | 3-4d | 文档评审 + 新格式 E2E |

**合计约 26-35 人日（≈5-7 周）**——较原路线图 4 周略扩，主因 MCP 提前（+1 周）与 bad case 闭环升级；否决 K8s/Milvus 两项（-5d）部分对冲。若需压缩：簇⑤可移后段或裁剪 `ask` 工具、簇⑦可拆散伴生。

### 5.2 顺序理由

- **①② 先行**：观测是「运营闭环」的眼睛，且簇①的 trace 平台同时服务后续所有簇的调试（MCP 调用链、重建任务链路都可观测）；
- **③ 承接优化冲刺**：C1/D3 铺好的基座趁热消费，kb-admin 模块首建；
- **④ 在①后**：Bad Case 面板需要观测数据（拒答率/空检索率）作为标注入口线索；
- **⑤ 居中偏后**：依赖簇①的观测能力验证 MCP 调用链，且安全治理要求护栏/审计全就绪（已就绪）；
- **⑥ 压轴**：压测需要全功能就绪才有意义；Flyway/备份在任何进一步 schema 变更前落地更稳；
- **⑦ 收尾**：文档覆盖全部新端点。

### 5.3 每簇 DoD（沿用优化冲刺口径）

1. 代码 + 单测绿（涉及模块 `mvn -q --no-transfer-progress test`，新路径并入 D3 集成测试网）
2. 验证通道通过，证据落进度文档任务行
3. 文档回写：进度文档 + 设计回写（见第六节清单）+ CLAUDE.md 同步
4. git 提交（一功能一提交，代码+文档同批）

---

## 六、设计回写候选清单（定案后执行，不在本提案内代改）

| 章 | 回写内容 |
|---|---|
| 第八章 | Phase 4 任务清单按本提案重写（含验收标准修订），v2 修订注记 |
| 第十三章 | 13.1 OTel Java Agent → Micrometer Observation + OTel SDK 导出修正；13.5 Langfuse 自托管形态修正（Cloud 免费档 / Phoenix 自托管二选一，资源数据入档）；13.4 面板微调（TTFT/护栏命中/Bad Case 分布） |
| 第十四章 | 14.1 草图实现期源码级核验回写（vectorStore.delete / embeddingModel.embed API 形态、idType=TEXT 接线后的删除契约） |
| 第十七章 | 整体修订：K8s/Milvus 分布式 → Docker Compose 生产化 + 灾备最小集 + 压测方法论（Flyway/AppCDS 入档） |
| 第十八章 | 可用性 99.5%、QPS → TTFT/检索 P95/并发会话口径、Trace 采样口径 |

---

## 七、定案记录（2026-08-13 用户拍板）

1. **MCP Server 提前至 Phase 4**：✅ 采纳，完整形态（簇⑤：三件套 + Casdoor JWT 鉴权 + scope 治理 + 审计复用）
2. **LLM 观测平台**：✅ **Langfuse Cloud 免费档**（50k observations/月、2 用户、30 天保留；trace 数据出境的 SaaS 合规风险用户已知悉接受；重估触发：合规要求变化或流量超免费档 → Phoenix 自托管备选已在档）
3. **三项复审裁决**：✅ 全部确认——K8s/Milvus 否决（Compose 生产加固 + Flyway + 灾备最小集替代）、压测新口径（检索 P95 <500ms / TTFT P95 <2s / 20 并发会话）、可用性 99.5%
4. **周期口径**：✅ 完整版 5-7 周，七簇推进

**定案后动作**：第八章 Phase 4 任务清单已按本提案重写（v2.29），进度文档任务表同步；簇①（观测地基）为开工首项。

---

## 附录 A：调研来源清单（2026-08-13，四路并行调研）

**可观测**：OpenTelemetry GenAI Semantic Conventions（opentelemetry.io/docs/specs/semconv/gen-ai/）· Langfuse 部署文档与定价（langfuse.com/docs/deployment/self-host、/pricing）· Arize Phoenix 文档（phoenix.arize.com）· SigNoz/OpenLIT LLM 可观测文档 · Micrometer Tracing 参考（micrometer.io/docs/tracing）· Spring AI Observability（spring.io/blog）· Grafana LLM Observability（grafana.com/oss/llm-observability/）· Langfuse Bad Case 工作流博客 · 阿里云可观测 LLM 应用监控文档
**产品化对标**：Dify 1.12 知识库文档与 changelog（docs.dify.ai）· RAGFlow 知识库配置与 changelog v0.21/v0.24（ragflow.io）· MaxKB V2 RBAC 与定价（maxkb.cn）· Onyx 连接器与 ACL 文档（onyx-dot-app/onyx GitHub）· Langfuse Prompt Management 文档 · Casdoor 权限配置文档 · 阿里云 AppFlow 飞书→百炼同步方案、DTS 钉钉→RAGFlow 同步方案
**MCP 与 Agentic**：MCP 规范 2026-07-28 发布博客与 Streamable HTTP/Authorization 章节（modelcontextprotocol.io）· 美国 DoD《MCP Security》报告（2026-06）· Spring AI MCP Server/Client starter 文档（docs.spring.io/spring-ai）· spring-ai#6465 · spring-ai-community/mcp-annotations · Cloudflare Agents HITL 文档 · Oracle OIC Knowledge Base MCP 博客 · Vectara/Notion MCP Server 文档
**部署与压测**：Gatling LLM API 压测指南与 SSE 压测博客（gatling.io）· Milvus 部署选项/Woodpecker WAL/资源要求文档（milvus.io）· k6 SSE Issue #746 与 xk6-sse · NVIDIA NIM/Anyscale LLM 基准指标文档（TTFT/TPOT 定义）· Docker Compose vs K8s 小团队实践多篇（Medium/Dev.to 2026）· Paketo Spring Boot 性能博客（GraalVM/CRaC/AppCDS）· DeepSeek 可靠性报告
