# Phase 4：运维观测与产品化

> 本文档为《项目阶段推进任务清单完成记录》2026-08-21 拆分子卷（仅结构调整，内容为原始记录逐字保留）；索引导航见[主文档](./项目阶段推进任务清单完成记录.md)。

**目标**：打造可度量、可迭代的 AI 运营闭环，并以 MCP 开放完成「企业知识底座」产品化叙事

> **v2.29 复审重写（2026-08-13）**：四路网络调研实证 + 用户定案，任务清单重写（裁决要点：4.1 观测形态修正——Java Agent 零代码路径其 Micrometer Bridge 默认关、强开与既有 17 项 rag.* 指标重复导出风险，选 OTel Spring Boot Starter/SDK 路径 + OTLP 导出（簇① 实现提示含 Arconia 语义约定切换器与告警阈值参考值，见规划方案 5.1 注）/ K8s 与 Milvus 分布式否决改 Compose 生产加固 + Flyway / 压测 QPS 口径废止改 TTFT/检索 P95/并发会话 / 可用性 99.5% / 原 4.7 PromptTemplateManager 自建否决改 Git Ops / 原 4.5 级联删除簇⑥ C1 已完成降为验证项 / 5.11 MCP Server 提前 / LLM 观测定案 Langfuse Cloud 免费档）。完整裁决依据与调研来源见 `docs/project-optimization/Phase 4 复审与规划方案（调研实证版）.md`；推进形态为七簇成簇推进（观测地基→面板统计→Chunk 运维与重建→Bad Case 闭环→MCP→生产加固压测→文档收尾）。

### 任务清单（14 项，v2.29 重写）

| # | 任务 | 负责模块 | 工时估算 | 验收标准 | 完成情况 |
|---|------|---------|---------|---------|---------|
| 4.1 | LLM 观测链路：Micrometer Observation → OTel SDK OTLP 导出 Langfuse Cloud（采样可配 + 错误/低分反馈全量；gen_ai.* 键名经映射层间接引用） | kb-api/工程基础 | 1.5d | LLM 调用树在 Langfuse 可见 | ✅ 2026-08-14 簇①（三件套依赖 + OTLP 全路径/Basic Auth 实证 + 内容桥接 convention + 采样 TRACING_SAMPLING 可配；E2E：GENERATION span prompt/completion/token 全可见。**碎片化当日定案修复（v2.31）**：ChatClient.builder 单参 NOOP registry 根因 + BaseAdvisor publishOn 线程跳跃，双链显式装配 registry + Controller Context 桥接，E2E 28 观测合树；残余 embedding/rerank 检索层 span 留簇②/⑥；错误全量随采样=1.0 天然覆盖，低分反馈关联留簇④） |
| 4.2 | Prometheus 告警规则：基础设施层 + LLM 特有五项（反馈比/空检索率/拒答率/Token 突增/供应商错误率） | 部署 | 1d | 告警自检触发 | ✅ 2026-08-14 簇①（infra/prometheus/alert-rules-kb-rag.yml 11 条：JVM/进程 4 + LLM 特有 7，阈值调研参考值待基线校准；**验证口径修正**：ECS 无 Prometheus，语法自检通过，真实触发自检随簇⑥ Compose 部署） |
| 4.3 | Grafana 四面板（概览/检索/LLM/业务，补 TTFT/路由决策/护栏命中/Bad Case 分布） | 部署 | 1.5d | 面板自采核验 | ✅ 2026-08-15 簇②。infra/prometheus/prometheus.yml（接线簇① 告警）+ Grafana provisioning（datasource uid 钉死 + 面板 provider）+ 四面板 JSON（21 面板 36 表达式，actuator 实证名；无指标支撑面板定案不设：活跃用户/Bad Case 分布/单路延迟，理由入面板口径说明，13.4 定稿表）+ AiBusinessMetrics +rag.ttft Timer + retrieval.latency publishPercentiles 补齐。**本地 compose 自核验通过**：promtool check config + targets up + 36/36 表达式对活 Prometheus 校验 + provisioning 加载 + 真实流量喂数。**trace 残余试修同批裁决**：两级执行器传播包裹（ContextPropagatingTaskDecorator + ContextExecutorService.wrap）→ Jaeger 实证 embedding×2 合树 ✓；rerank 源码核验裸 RestClient 不产 observation（无碎片问题）；新残余流式主生成 HTTP POST 留簇⑥（候选 Reactor 全局钩子）。验证通道：本地 Jaeger OTLP（不依赖 Langfuse 凭据，13.7） |
| 4.4 | Chunk CRUD 运维 API（编辑→异步重嵌入/软删/恢复，复用 C1 软删管道与 ChunkCleanupService） | kb-admin | 2.5d | 编辑后检索命中新内容 E2E | ✅ 2026-08-15 簇③（实现批，E2E 待用户自测）。kb-admin 首建承载：ChunkAdminController 三端点（PUT 编辑 / DELETE 软删 / POST restore）+ ChunkOpsService——编辑同源消毒 → PG 同步 → etlExecutor 异步两步重嵌入（delete→add，Milvus add 非 upsert 实证）+ ES 覆写；软删委派 C1 管道幂等；恢复 CHUNK_NOT_DELETED 409 守卫 + 重嵌入复位；租户守卫 @AuthenticationPrincipal Jwt 直消费（owner claim，不复用 JwtUtils 防成环）；守卫 fail-closed（跨租户/不存在 → CHUNK_NOT_FOUND，处理中 → DOC_NOT_READY 409）；rag.chunk.* 三计数。vectorMetadata 契约静态抽取共享 ETL。14.1 草图三处实证修正回写（14 章 v2.33）。kb-admin 25 单测绿。**E2E 待用户自测：编辑后检索命中新内容/软删不可检/恢复复现** |
| 4.5 | 索引全量/增量重建 API（蓝绿管线复用 + ES heading_path mapping 对齐窗口；文档级联删除验证并入） | kb-admin | 2d | 漂移修复收敛；10万 chunk 重建 <30min | ✅ 2026-08-15 簇③（实现批，E2E 待用户自测）。RebuildController（POST rebuild 全量/目标 + 任务表轮询）+ IndexRebuildService——ReindexGateway 依赖倒置委派 kb-api reparse（终态 future 汇聚 ETL 终态帧，零重复编排）+ 滑动窗口并发 4 + 单文档超时 30min；**漂移收敛 = 蓝绿 diff（C1 既有）+ ES 孤儿清扫**（每文档成功后 ES 全集 − PG 现存集 → deleteByChunkIds；向量孤儿留离线清理）；内存任务表 FIFO 20 重启丢失为既定形态（**v2.36 已迁 Redis，见 2026-08-16 状态块**）；heading_path mapping 对齐经全量重建窗口天然消化（indexChunks 每 chunk 覆写）。10 万 chunk <30min = embedding 吞吐主导外推论证（14.2）+ E2E 实测外推校准。IndexRebuildService 6 单测绿。**E2E 通过（2026-08-15 用户自测，随簇④ 运维中心重建面板验证：任务 RUNNING→COMPLETED 轮询收敛 + 失败明细）** |
| 4.6 | 知识库统计 + 文档解析状态 API | kb-api | 1d | 仪表盘数据接口 | ✅ 2026-08-15 簇②。KbDocumentRepository +6 聚合（状态/路由分组 + CAST 日期趋势 + chunk_count 求和 + 处理中清单）+ StatsService（状态五值补 0 / 路由 null→UNKNOWN / 14 天趋势补 0）+ StatsController `/api/v1/stats/overview`（文档总数/状态分布/chunk 总量/路由分布/入库趋势）与 `/api/v1/stats/documents/processing`（处理中三态计数+清单），租户守卫同 FeedbackController（IDENTITY_INCOMPLETE）。聚合走 kb_document 单表（chunk 取文档侧口径，软删精确口径属簇③）。**E2E 真实数据通过**（用户 curl）：6 文档全 SUCCESS / 168 chunk / NATIVE 5 DEEP 1 / 08-12 趋势尖峰 / 处理中空清单全对。**遗留口径 2026-08-15 簇③ 收口**：overview.chunkTotal 切 kb_chunk JOIN 存活精确计数（countAliveByTenantId：租户 + 排除软删；趋势仍文档侧近似），StatsServiceTest 同步更新 |
| 4.7 | Bad Case 运营闭环（日志查询 + 根因标注四分类 + Golden Set 回灌通道） | kb-admin + kb-eval | 3d | 一轮真实闭环（标注→回灌→CI 复跑） | ✅ 2026-08-15 簇④（实现批，E2E 待用户前端自测）。BadCaseAdminController + AuditLogQueryService + BadCaseService（kb-admin）：① `GET /admin/audit-logs` 多选项分页查询（时间/用户/会话/反馈/状态/根因/标注态，租户恒过滤）+ feedbackExpectedAnswer 批量联查；② `PUT /audit-logs/{id}/root-cause` 四分类（RootCause 枚举落 kb-domain，kb_audit_log 新列 root_cause + 复合索引，**ECS 先 ALTER**）；③ `POST /badcase/reingest` **Golden 回灌 Git Ops 通道**——审计行转用例写 badcase-qa.json（bc-{auditLogId} upsert，成功联动反馈 resolved），回灌→commit→CI 复跑闭环；④ `PUT /feedback/{id}/resolved` 处理态。守卫 AUDIT_LOG_NOT_FOUND 不泄露存在性 + 错误码族；rag.badcase.annotate/reingest 指标。kb-admin 42 单测绿（+v2.35 AuditLogSpecsTest 3 = 45）。**v2.35 形态修正**：@Query 可选参数 PG 预编译类型推断缺陷 → AuditLogSpecs Specification 动态谓词（见 14 章 v2.35 注记）。**E2E 通过（2026-08-15 用户自测）**：标注→回灌→CI 复跑一轮真实闭环达成，含 v2.35 修复复验（运维中心跨预编译窗口切换无 500） |
| 4.8 | Prompt 外部化（Git Ops：模板收编 yml/常量类） | kb-ai-core | 0.5d | 外部化率 100%（第 18 章验收） | |
| 4.9 | 前端运维审计看板（统计仪表盘 + 日志查询 + Bad Case 标注） | 前端 | 3d | 可视化运维面板 | ✅ 2026-08-15 簇④（实现批，E2E 待用户自测）。新页 `/admin` 运维中心（Layout 导航接入），四 Tab 一条工作流（v2.35 扩展 +② Chunk 运维：编辑/软删/恢复 + 重建面板，后端零改动）：① 统计仪表盘（消费簇② stats overview/processing + Bad Case 队列计数：点踩总数/待标注数）；② 日志查询（时间/用户/会话/反馈/状态过滤条 + 表格 + 行详情抽屉，改写/回答/重排序列/双路命中/工具调用 JSON pretty 展示）；③ Bad Case 处置（四分类标注对话框带释义 + 回灌对话框：检索快照解析候选 chunk/文档快填 + 期望回答预填 feedbackExpectedAnswer）。api/index.ts +6 端点封装。vue-tsc + vite build 绿。**E2E 通过（2026-08-15 用户自测）**：四 Tab 界面操作验证全达成（并入簇③ 顺延项：chunk 编辑命中/软删恢复/重建收敛），自测期弹层微调三笔用户自行合入 |
| 4.10 | ★ MCP Server 宿主（5.11 提前）：`@McpTool` 三件套 + Casdoor JWT OAuth + scope 治理 + 审计/护栏链复用 | kb-api | 5-7d | 标准 MCP Client 真实调用 + 跨租户隔离 0 泄露 | ✅ 2026-08-16 簇⑤（E2E 通过）。spring-ai-starter-mcp-server-webmvc（Streamable HTTP /mcp，注解扫描注册）+ McpKnowledgeTools 三件套落 kb-ai-agent（search 检索链直调 / get_document 租户守卫+软删过滤+截断 / ask ragAgentChatClient 全链复用——护栏/配额/审计自动生效）+ McpIdentityGuard（JWT owner→tenantId fail-closed + rag.mcp.scope.required scope 治理）+ rag.mcp.* 三计数。E2E 三轮收敛：坑位㉚ protocol STREAMABLE 显式钉（连接修复）→ v2.38 审计会话 ID 36 字符钉死 + 注入词表中文补强。详见 2026-08-16 簇⑤ 状态块与 11 章 §11.8。**E2E 通过：三件套真实调用 + 跨租户隔离 + 注入拒答 + 计数自采全达成** |
| 4.11 | Docker Compose 生产加固 + Flyway + 灾备最小集 + AppCDS | 部署 | 2.5d | 备份恢复演练 + 99.5% 兜底自检 | 🔄 2026-08-20 批1a Flyway 落地（v2.54：starter + flyway-database-postgresql 12.4.0；V1 基线 = schema.sql 十表幂等全量快照；baseline-on-migrate ECS 现网库零变更登记；同源双写纪律 + 双源一致性单测；kb-eval 主配置关 / IT 重开回归保险，7 章 §7.6）。批1b/2/3 见下方簇⑥节 |
| 4.12 | Gatling 压测（检索真压 + 生成桩压 + 小样本真实 LLM + SSE 并发） | 测试 | 2d | 压测报告（新口径） | |
| 4.13 | 运维手册 + API 文档 + 用户使用手册 | 文档 | 2d | 完整文档交付 | |
| 4.14 | PPT/Excel 格式支持（白名单扩容 + Tika 解析 + E2E） | kb-api/kb-etl | 0.5d | 新格式上传入库 E2E | |

### 交付物

- [ ] LLM 全链路观测（Langfuse Cloud + Grafana 四面板 + 告警规则）
- [ ] kb-admin 运维模块首建（Chunk CRUD + 索引重建 + 统计 API）
- [ ] Bad Case 运营闭环（查询→标注→回灌→CI 复跑）
- [ ] Prompt Git Ops 外部化
- [ ] MCP Server（三件套工具 + OAuth/scope 治理）
- [ ] Docker Compose 生产加固 + Flyway + 灾备最小集
- [ ] Gatling 压测报告（LLM 专用指标口径）
- [ ] 运维手册 + API 文档 + 用户手册

### Phase 4 验收标准（v2.29 修订）

| 指标 | 目标值 |
|------|--------|
| LLM 调用可观测 | Langfuse 调用树可见；采样可配 + 错误全量 |
| 生产环境可用性 | > 99.5%（单机现实口径；99.9% 需多节点，重估触发留档） |
| 检索链路 P95（真压） | < 500ms |
| TTFT P95（小样本真实 LLM） | < 2s |
| 并发流式会话 | 20 稳定 |
| 10万 Chunk 索引重建时间 | < 30min |
| Bad Case 可回溯率 | 100% |
| Prompt 外部化配置率 | 100%（Git Ops 形态） |
| DB 迁移版本化 | Flyway 100% |
| MCP Server 兼容性 | 标准 MCP Client 可正常调用 + 租户隔离 0 泄露 |

## Phase 4 簇⑥：生产加固与压测（2026-08-20 开工，v2.30 顺延项，安全专项收官后接力）

> 依据：第 8 章任务 4.11/4.12 + Phase 4 复审方案 §5.1 簇构成表行⑥（含双供应商 SLA 监控）+ 各章「留簇⑥」登记项（13 章 v2.32 trace 残余 / 13.6 告警真触发与 node_exporter / 13.7 监控栈生产化 / 12.9 B4 ECS 安全组）。形态：每批 = 实现 → 验证 → 文档回写 → 提交；E2E = 机器侧资产 + 步骤交付，用户侧 ECS 自测回传。**用户定案（2026-08-20）**：① ECS 形态 = PG 宿主机原生安装、ES/Redis/MinIO/Milvus 既有 Docker Compose 部署；② 流式 trace 残余 = 诊断 + 尽力修复（设投入上限，超限降级留档）；③ 挂起项不顺带。机器侧就绪用户侧待跑项收归 `docs/project-progress/用户侧待执行项清单.md`。

| 批 | 构成 | 验收通道 | 完成情况 |
|---|---|---|---|
| 1a Flyway 迁移版本化 | kb-domain 引入 spring-boot-starter-flyway + flyway-database-postgresql（12.4.0 Boot 4.1 BOM）；V1__baseline_schema.sql = schema.sql v2.53.1 十表幂等全量快照；baseline-on-migrate=true + baseline-version=0（ECS 现网库首跑 baseline 登记 + V1 幂等 no-op 零变更 / 空库直建）；同源双写纪律（schema 变更先 V(N+1) 再同步 schema.sql 快照）+ SchemaDualSourceConsistencyTest 表集守卫；kb-eval 主配置关（度量工具不改目标库）/ IT 显式重开（baseline 路径持续回归） | ECS 备份库演练 + flyway_schema_history 校验（用户侧，清单文档条目 F1） | ✅ 2026-08-20 机器侧（全 reactor 669 绿 +1 双源守卫，7 章 §7.6 v2.54） |
| 1b 容器化与编排 | 根 Dockerfile（eclipse-temurin 21 JRE + 宿主构建 fat jar + curl HEALTHCHECK；**AppCDS 部署侧训练形态**——docker-entrypoint.sh 探测 /opt/cds/app.jsa 动态挂 SharedArchiveFile，缺失回落普通启动）+ docker-compose.app.yml（分层编排不纳管存量：restart/healthcheck/日志轮转 50m×3/内存限额 env 可调 + `KB_IMAGE_TAG:?` 禁 latest + kb-api-cds-train tools profile 训练服务 + 词表存档卷）+ .env.example（全连接凭据模板，host.docker.internal 主机名约定）+ kb-api.service（systemd oneshot 开机自启，进程自愈归 compose）+ build-image.sh（tag v{semver}-{git 短哈希}）。连接形态定案：PG 原生 + 存量 compose 统一经 host.docker.internal（host-gateway），共享网络直连为可选优化 | 镜像构建 + compose config 校验 + compose up healthy + CDS 训练实测 + reboot 自启（用户侧，清单 F3） | ✅ 2026-08-20 机器侧（compose 双形态 config 校验过；17 章 §17.4 v2.55），ECS 部署待用户 |
| 2 监控栈生产化 | docker-compose.monitoring.yml 生产化（四服务 restart + healthcheck + 限额 + 日志轮转 10m×3 + Grafana 口令 infra/.env 注入 `GRAFANA_ADMIN_PASSWORD` :? 守卫，明文 admin/admin 终结）+ node_exporter v1.9.1 入栈（host pid + / 只读挂载官方形态）+ kb-rag-host 告警组激活（HostDiskNearFull/HostMemoryNearFull 注释转正，告警 11→13 条）+ **自检矩阵 13 条全覆盖**（6 真触发演练 + 7 promtool 合成单测 alert-selfcheck/，数值构造防 increase() 外推歧义）+ kb-monitoring.service 开机自启 + prometheus.yml node-exporter job（targets 口径定案：分层 compose 经 host.docker.internal:8090 抓宿主端口，与批1b 一致，共网络改服务名为可选优化）。实证：Jaeger all-in-one scratch 基座容器内无 shell——healthcheck 不可行，自愈靠 restart | targets up + 13 条自检 FIRING/单测留档 + 面板活数据（用户侧，清单 M1） | ✅ 2026-08-20 机器侧（compose config 校验过 + :? 守卫拒起验证；13 章 §13.6/13.8 v2.56），ECS 部署与自检待用户 |
| 3 灾备最小集 | pg-backup.sh（pg_dump -Fc → 本地 + MinIO kb-backups 双副本，--md5 校验 + flock 锁 + 7 天保留 + cron 每日 03:07）+ pg-restore-drill.sh 一键恢复演练（独立演练库 + 表集/行数三态判定，--strict/--from-minio/--api-check）+ 99.5% 兜底自检清单（docs/project-operations/，10 项）+ .env.example 备份段 | 恢复演练行数一致 + 自检全 ✅（用户侧，清单 DR1） | ✅ 2026-08-21 机器侧（脚本语法与解析逻辑核验过；17 章 §17.5 v2.57），ECS 演练待用户 |
| 4 残余消化 + SLA | 流式主生成 POST 独立 trace Jaeger 诊断 + 尽力修复（13 章 v2.32，2h 上限超限降级留档）+ ECS 安全组 actuator 源址限制（12.9 B4）+ AiBusinessMetrics +3 计数器（circuit.opened/half-opened/fallback.invoked）+ kb-rag-supplier-sla.json 面板 + KbPrimaryModelDegraded 告警 | Jaeger 合树或降级留档 + 安全组生效 + 面板/告警自检（用户侧） | 待启动 |
| 5 Gatling 压测（压轴） | 独立模块 kb-loadtest：检索真压 P95<500ms / 生成桩压（内置 OpenAI 兼容 SSE 桩服务）/ 小样本真实 LLM TTFT P95<2s TPOT<100ms（计费敏感，用户确认后执行）/ 20 并发 SSE 稳定；最小连通 scenario 先行；报告入 docs/reports/ + 18 章回填 | 压测报告四场景（用户侧 ECS 执行） | 待启动 |

> 收口判据：4.11 验收（备份恢复演练 + 99.5% 兜底自检）+ 4.12 验收（压测报告新口径）+ 残余清偿或降级留档 + 文档回写（17 章整体重写 ECS 单体形态 / 13/15/18 章 / README / CLAUDE.md）。簇⑥收口即 Phase 4 全阶段收官，下一棒簇⑦文档与格式收尾（4.13/4.14）。
