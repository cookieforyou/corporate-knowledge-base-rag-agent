## Phase 5：高级特性与持续进化（第 17-24 周）

> 本文档为《项目阶段推进任务清单完成记录》2026-08-21 拆分子卷（仅结构调整，内容为原始记录逐字保留）；索引导航见[主文档](./项目阶段推进任务清单完成记录.md)。
>
> **复审导航（2026-08-22 定案）**：下表 12 项已经立项复审逐条裁决（销项 5.7/5.11、收窄 5.3/5.12、调整 5.1/5.9、归档 5.4、降级登记 5.5、保留 5.2/5.6/5.8/5.10），并新增三项（供应商侧 Context Cache / 前六阶段收尾清零簇 / A2A 前瞻）、重组为六簇推进（收尾清零 → 评估进化 → 语义缓存 → GraphRAG → Agent 编排 → 产品化收尾）。**推进以复审方案为基线**：[Phase 5 复审与规划方案（调研实证版）](../project-optimization/Phase%205%20复审与规划方案（调研实证版）.md)；路线图修订见 08 章 v2.63。本卷下文任务表作为原始登记保留，各任务行进展回填仍落本卷。

**目标**：GraphRAG、Multi-Agent、智能路由、反馈闭环

### 任务清单（12 项）

| # | 任务 | 负责模块 | 工时估算 | 验收标准 | 完成情况 |
|---|------|---------|---------|---------|---------|
| 5.1 | 部署 Neo4j + 知识图谱构建管道 | kb-infrastructure/kb-etl | 5d | 实体关系自动抽取入库 | |
| 5.2 | 实现 GraphRAG 混合检索（Vector + Graph 双引擎） | kb-ai-core | 5d | 多跳推理问题可正确回答 | |
| 5.3 | 实现 Multi-Agent Orchestrator（主Agent+子Agent 模式） | kb-ai-core | 5d | 复杂任务自动分解执行 | |
| 5.4 | 实现查询意图识别 + 自适应路由（分类器模型） | kb-ai-core | 3d | 不同意图分流到不同策略 | ✅ 收窄版完成（2026-08-08，用户拍板，当日 E2E 通过；**剩余两项 2026-08-09 缓做定案**，与 3.18 同步前移至 Phase 4 立项前：A 跨链 mode=auto 价值拐点在真实工具落地，作 Phase 4 伴生项；B 复杂度三级路由缺轻量档模型前提且无成本痛点，触发条件=引入轻量档位）。**动因**：3.15 E2E 实证 ① grounding 强约束（「必须且只能基于【参考资料】」）语义层压过 Memory(400) 注入历史——「我刚才问了什么」被拒答（bm25 召回 10 条噪声未触发空证据模板，是强约束拒答非记忆故障）；② 闲聊/元问题白付改写+检索+重排 ~5s 前置开销。**落地形态**（设计锚点 §11.4，四处修正/强化见 11 章 v2.13）：QueryRoutingAdvisor(order 440) 双层分类——L1 正则快路（整句全匹配纯寒暄/致谢/道别/助手元问题，≤15 字符）零 LLM；L2 结构化分类 `entity(IntentResult)`（直注 agentChatMemory 经 CONVERSATION_ID 读最近 6 条历史，不依赖 MessageChatMemoryAdvisor 内部排序）；**分类与改写合并单次 LLM 调用**——KNOWLEDGE 路径预写 `RetrievalContext.rewrittenQuery`，RewriteCapturingQueryTransformer 识别后跳过自身调用，知识问零新增延迟；RetrievalGateAdvisor(order 500) 实现 CallAdvisor+StreamAdvisor 组合式包裹 RetrievalAugmentationAdvisor（源码核验框架类 final 不可 extends，草图「自身读标记短路」不可行）——skipRetrieval 时 chain.nextCall/nextStream 直接放行旁路整套管线；**fail-open 纪律**（分类异常/解析失败/未知 intent 回落完整检索，最坏=现状）；AiBusinessMetrics + rag.routing.chitchat/knowledge 分流计数；闲聊路径免 TRACE 帧（对齐「不推空帧」纪律）；`rag.routing.intent.enabled`/`history-size` 配置（总开关 false 回退现状）；defaultSystem 双形态措辞（知识问证据约束仍由 GROUNDING_PROMPT 每请求注入保证）。mode 契约/前端零改动，kb-eval 独立 chatClient 零影响。设计回写 11 章 v2.13；新增 21 单测（分类 13/门控 5/装饰器 2/指标 1），全模块 211 绿。**E2E 通过（2026-08-08，用户验证）**：寒暄/元问题 CHITCHAT 免检索直答（元问题正确复述上一问）、知识问回归完整溯源、空证据拒答负向通过、rag_routing_* 计数匹配（见《00-每日进度状态记录》2026-08-08 状态行）。**剩余形态设计锚点**：A 跨链 mode=auto 路由器位于两链之上（Controller/Service 层选链，异于链内 440）+ approvedToolCallId 非空硬路由 tool 链 + fail-open 回落 rag；B 需先引入轻量模型档位（3.2 定案「双模型下形同虚设」）+ Prompt options 重建（坑位⑭）+ kb-eval 防误路由护栏 |
| 5.5 | 实现多知识库动态路由（不同领域独立 VectorStore） | kb-ai-core | 3d | 按领域自动选择知识库 | |
| 5.6 | 实现 Semantic Cache（Redis RediSearch + 语义相似度） | kb-ai-core | 3d | 相似问题缓存命中率 > 30% | 🟡 **批1+批2 完成（2026-08-24/25）**：批1 `SemanticCacheService` 核心——Redis 8 内建搜索引擎（FT.* VECTOR HNSW/COSINE）经 Redisson `RSearch` 类型化 API 零新增依赖（否决 Spring AI redis 向量模块，Jedis 双客户端风险）；每租户索引域隔离 + KNN top-1 余弦阈值判定（0.95 保守起步）+ 问句指纹确定性键幂等写入 + 按文档 TAG 反查失效 + 能力探测自关 + 全路径 fail-open；13.3 预留位 `rag.retrieval.cache.hit` 启用（+miss/invalidated）；`rag.cache.*` 配置族缺省关；11 章 v2.71 §11.9。批2 `CacheCheckAdvisor` order=460 接线——命中短路重放（缓存回答单帧 + 溯源载荷回填，TRACE/审计/归档同形）/ 未命中流末聚合异步写入（五重门槛：rag 链结构性 + 单轮 + 非闲聊 + 流正常完成 + final 证据非空；嵌入单次复用）；事件失效四处（ETL COMPLETED 终态帧覆盖 reparse/replace/重建/首次入库——重建经 ReindexGateway 同路径不重复接线 + Chunk 编辑/软删/恢复）经 `rag:cache:invalidate` pub/sub；`kb.cache.semantic` 观测 span；条件装配 + ObjectProvider 容忍（关闭态零变化）；11 章 v2.72 §11.5.1/§11.9；全反应器 817 单测绿（+25）。批3 N1 定案完成（2026-08-25，零代码：DeepSeek 前缀缓存默认开 + 遥测面裁决 + §11.10 入档）——**机器侧收口**（用户侧 E2E 热修 2 件：㉝ Redisson hasIndex 文案不兼容 / ㉞ 配置类注册遗漏，11 章 v2.74；㉟ TAG 查询转义缺失，11 章 v2.75），**用户侧 E2E 2026-08-26 通过——簇③收官**（索引创建/命中冒烟/守卫/失效演练全过；延迟对照 miss 5-8s vs hit ≈2s 方向性越过 40% 线；命中率随真实流量观察期；⑥ N1 直调对照缓做登记；分支合并待批5 回传窗口） |
| 5.7 | 扩充 Golden Dataset 至 200+ 问答对（基线已在 Phase 2.16 建立） | kb-eval | 3d | 测试集覆盖主要场景 | ✅ **销项（2026-08-23 登记，Phase 5 复审裁决）**：提前达成——现 237 条（干净 110 + 注入 127，三分区门禁全绿），超额完成（200+ 目标），复审方案 §二 裁决留痕 |
| 5.8 | 扩充 LLM-as-Judge 评估管道（指标全集见第十六章） | kb-eval | 3d→4d（复审） | 全指标自动评分 + 人类校准 κ≥0.80（复审口径） | 🟡 **批1+批2 完成（2026-08-24）**：批1 四新指标扩管道落地观察带——AC（expectedAnswer 对照）/ CA 三步（发出→可解析确定性前置→来源支撑）/ HR 声明级 / NRob 抽样对照（缺省关）；阈值预留不门禁（校准纪律），16 章 v2.67。批2 人类校准通道——五维校准表双通道（材料 MD + 打分 CSV 双标注）+ Cohen's κ 计算器（名义/二次加权）+ 回读器（`--eval.calibration-readback`，对照 `eval.calibration.kappa-target=0.80`）+ expectedAnswer 机器侧草稿生成器（`--eval.draft-answers`，PG 真值直查零循环，用户定案机器侧草稿 + 人工审定），16 章 v2.68；全模块 740 单测绿（批1+15 / 批2+28）。余批5 用户侧读数：50 例双标注 + κ 定档 + 草稿审定回写 |
| 5.9 | 实现 A/B 测试框架（Prompt 效果对比） | kb-eval/kb-ai-core | 3d | 双版本效果量化对比 | ✅ **收窄版完成（2026-08-24，簇② 批3）**：复审裁决轻量化形态落地——基于 Prompt Git Ops（4.8）prompt 版本即 git 版本，不新建抽象层。三构件：① 报告头运行锚点（git 哈希 + 提交时间 + 工作区脏标 + 运行时刻，非 git 降级 UNKNOWN）；② 机读快照 `eval-results{-label}.json`（锚点 + 配置 + 聚合 + 逐用例读数，**内容盲**：答案仅存 SHA-256 指纹）；③ `--eval.diff=labelA,labelB` 差异报表（配置一致性核验 + 聚合 Δ 对照 `eval.thresholds.regression` 容忍带判 IMPROVED/REGRESSED/STABLE + 分类分解 + 逐例翻转/异动/指纹变化 + 用例集漂移告警），零 LLM 调用。16 章 v2.69；全模块 759 单测绿（+19） |
| 5.10 | 实现反馈闭环导出管道（JSONL SFT 格式） | kb-admin | 2d | 可用于模型微调的数据导出 | ✅ **收窄版完成（2026-08-24，簇② 批4）**：复审裁决「只导出双格式、不绑定微调平台」落地——kb-admin `FeedbackExportService` + `FeedbackExportAdminController`：双格式派生确定性规则（SFT 双通道 = 👍 采纳 [问题+系统原回答] + 👎 订正 [问题+用户 expectedAnswer]；DPO = 👎+期望回答+原回答齐备 → 同问题 chosen=订正/rejected=原回答，二元反馈天然成对零跨会话配对）；质量三重过滤（审计三态 REJECTED/ERROR 不入训练材料 / 共享 PII 注册表同款掩码 / 会话材料完整性）；格式对齐百炼 ChatML（SFT 不烘焙 system），门槛 SFT≥100/DPO≥50 对只报告不门禁；端点 = summary（dry-run 计数）+ export?format=sft\|dpo（JSONL 附件）。16 章 v2.70 §16.6.1；全模块 777 单测绿（+18）；导出 ≥200 例验收计数并批5 用户侧 |
| 5.11 | 实现 MCP Server 宿主（知识库对外暴露为 MCP 服务） | kb-api | 3d | 第三方 Agent 可调用知识库 | ✅ **销项（2026-08-23 登记，Phase 5 复审裁决）**：已在 Phase 4 4.10 提前交付（v2.60，§11.8 三件套 + 身份守卫 + 独立限流审计，标准 Client 兼容与跨租户隔离验证通过），复审方案 §二 裁决留痕 |
| 5.12 | 企业微信/钉钉集成适配 | kb-api | 3d | 工作 IM 内直接问答 | |

### Phase 5 验收标准

| 指标 | 目标值 |
|------|--------|
| GraphRAG 多跳推理准确率 | > 80% |
| Multi-Agent 任务完成率 | > 85% |
| Semantic Cache 命中率 | > 30% |
| 自动评估 CI 集成 | 每 PR 自动运行 |
| MCP Server 兼容性 | 标准 MCP Client 可正常调用 |

---

### 簇① 收尾清零（推进记录）

> 构成与 DoD 见复审方案 §五；机器侧小件先行，用户侧 7 项回传（清单唯一源）并行。

| 项 | 内容 | 状态 |
|---|------|------|
| 5.7/5.11 销项登记 | 上表任务行已标（提前收官留痕） | ✅ 2026-08-23 |
| rerank observation 补齐 | `kb.rerank` 观测包裹（v2.32 ④ 登记项清偿，13 章 v2.64）；单测 +2，kb-ai-core 242 绿 | ✅ 2026-08-23 机器侧（E2E 观察点 = Langfuse 检索链树下 `rerank qwen3-rerank` span，随下轮运行态核验） |
| B5 漏洞治理 | 11 项 CRITICAL 清零形态：五构件族版本覆盖 + kotlin-stdlib-common 声明点排除（12 章 v2.64）；全模块 699 单测绿 | ✅ 2026-08-23 机器侧；**复扫回传同日闭环**：8 CVE 摘 5（CRITICAL 11→3），残留三枚定性 = kotlin 修复线实为 2.4.20（未 GA 不升 RC，待上游，清单 B5-4）+ milvus 双 CVE 服务端 CPE 误判（`dependency-check-suppressions.xml` 族级限定抑制，本地验证复扫 CRITICAL→1；服务端真实治理 = 清单 B5-3 ECS Milvus ≥2.6.10）；日志三项报错（RetireJS/.NET/OSS Index）定性数据源噪声无大碍；12 章 v2.65；**B5-3 同日回传销账**（ECS Milvus 2.6.0→2.6.22，服务端治理闭环，12 章 v2.66） |
| TABLE 分类 Faithfulness 观察项 | 读数通道核验已备（簇④ E1：报告「生成侧分类分解」+ 单维不崩地板 3.5）；**销账判据定案**：并入簇② 5.8 首轮全量复跑读数——TABLE 分类 F ≥3.5 且门禁绿 → 观察项销账（3.800 读数归 Judge 噪声带）；<3.5 → 单维崩盘治理立项。**不在本簇跑数理由**：计费伴随运行态，且与用户侧 LT1 压测同期争 ECS/LLM 资源 | 📋 判据定案，销账并簇② |
| 告警 14 条校准 | 伴生 M1（监控栈生产部署 + 7 天真实流量窗，误报率 <5%） | ⏳ 依赖用户侧 M1 |
| 用户侧 7 项回传登记 | F1-F3/M1/DR1/SG1/LT1（LT1 为簇③硬前置） | 🟡 2/7：**LT1 ✅ 2026-08-23 通过**（四场景缺省并发全达验收线、零失败全 DONE，18 §18.4 实测列回填 v2.65，**簇③硬前置解除**；数值明细待报告补录）+ **B5-2 ✅ 复扫入档**（见 B5 行）；余 6 项待回传 |
| B5 残留跟踪 | B5-3 ECS Milvus 服务端升级 + B5-4 kotlin 2.4.20 GA 后版本抬升复扫（待上游触发，机器侧） | 🟡 **B5-3 ✅ 2026-08-23 闭环**（用户侧回传：2.6.0 → 2.6.22 ≥修复线 2.6.10，E2E + 对话检索冒烟正常；CVE-2026-26190 服务端真实攻击面治理完毕，族级 CPE 误判抑制保留）；唯余 B5-4 待上游 GA，不阻塞任何簇 |


---

### 簇② 评估进化（推进记录）

> 构成：5.8（四指标扩管道 + 人类校准 κ≥0.80）+ 5.9（A/B Judge 双跑差异报表，轻量化）+ 5.10（反馈导出 JSONL 双格式）；TABLE 观察项销账读数并入首轮全量复跑（判据见簇①表）。批次规划（2026-08-24 定案）：批1 四指标扩管道 → 批2 校准通道（κ 计算 + 50 例双标注交付）→ 批3 A/B 报表（报告锚点 + diff 生成器）→ 批4 导出管道（kb-admin）→ 批5 用户侧合并复跑（全量基线 + TABLE 销账 + E1 L2 门禁复跑合并为一次跑，省一轮计费；另含校准标注与导出 E2E）。

| 项 | 内容 | 状态 |
|---|------|------|
| 批1 四指标扩管道 | `JudgePrompts` +4 套 G-Eval Prompt；`EvalRunner` 逐用例接线（AC 对照 expectedAnswer / CA 三步确定性前置省 Judge / HR 声明级 0-100→0-1 / NRob 抽样噪声对照——同一基座 + GROUNDING_PROMPT 编号续接混排）；`EvalReport` 嵌套 `Phase5Metrics` 聚合 +「生成侧扩展（Phase 5 观察带）」小节；开关 `eval.metrics.phase5-enabled`（缺省开）/ `noise-sample-size`（缺省 0）；阈值预留不门禁（校准纪律，单测钉死）；16 章 v2.67 | ✅ 2026-08-24（全反应器 471 单测绿，+15） |
| AC 读数前置：expectedAnswer 标注 | 标注形态定案（2026-08-24 用户拍板）：**机器侧草稿 + 人工审定**——草稿生成器已交付（`--eval.draft-answers`：golden 标注 chunk PG 直查真值零循环起草，缺失回落探针标记重点审定；Judge 通道起草保跨厂商独立性）；人工审定回写 golden 语料后置入批5 复跑窗口 | 🟡 生成器就绪，审定回写并批5 |
| 批2 人类校准通道 | 既有 E1 单维一致率通道扩为五维校准通道：打分材料 MD + 打分 CSV（human_a/human_b 正交双标注）；`CohensKappa` 名义 κ（CA NO_CITATION 归并判负 / HR 比率>0 二值化 / NRob）+ 二次加权 κ（F/AC 1-5）+ E1 邻差≤1 一致率口径延续；回读器 `--eval.calibration-readback` 逐维三对 κ（Judge×A/Judge×B/A×B）对照 `eval.calibration.kappa-target`（0.80）判 PASS/FAIL；50 例正交双标注 = 批5 复跑 `EVAL_JUDGE_AGREEMENT_SAMPLE=50` 产出；`EvalResult` +noiseAnswer（NRob 人审需见答案 B） | ✅ 2026-08-24（全模块 740 单测绿，+28；16 章 v2.68；golden 标注指南补两节） |
| 批3 A/B 双跑报表 | 报告头锚点（git hash + 时间戳）+ `eval-diff(labelA, labelB)` 差异报表生成器 | ✅ 2026-08-24（`GitAnchor` 锚点解析 + `EvalSnapshot` 机读快照（内容盲纪律）+ `EvalDiffRunner` 差异报表；`eval.thresholds.regression` 预留容忍带接线；全模块 759 单测绿 +19；16 章 v2.69；golden 标注指南补 A/B 工作流节） |
| 批4 反馈导出管道 | kb-admin `FeedbackExportService`：kb_feedback + trace_id 关联 → JSONL 双格式（SFT 单轮 + DPO 偏好对），只导出不绑定平台（百炼通道门槛：SFT≥100/DPO≥50 对） | ✅ 2026-08-24（`FeedbackExportService` 双格式派生纯函数分类器 + `FeedbackExportAdminController` 双端点 [summary dry-run / export JSONL 附件]；SFT 双通道 + DPO 天然成对；审计三态 + PII 共享注册表 + 材料完整性三重过滤；格式对齐百炼 ChatML 不绑定；全模块 777 单测绿 +18 [服务 14/守卫 4]；16 章 v2.70 §16.6.1；无前端按钮，curl 消费） |
| 批5 用户侧合并复跑 | 全量基线（四新指标首读数）+ TABLE 观察项销账读数 + 清单 E1（L2 门禁）合并执行，一次性开关组合 = `EVAL_JUDGE_AGREEMENT_SAMPLE=50`（校准表）+ `EVAL_METRICS_NOISE_SAMPLE_SIZE>0`（NRob 抽样）+ `EVAL_GUARDRAIL_L2_ENABLED=true`；并行窗口另跑 `--eval.draft-answers`（AC 草稿，审定回写后 AC 才有读数）；复跑后双标注回填 → κ 回读定档 + 导出 E2E ≥200 例（批4 双端点已就绪：summary 对门槛 + export 双格式下载） | 📋 待用户侧执行（批1-4 机器侧全就绪） |

---

### 簇③ 语义缓存（推进记录）

> 构成：5.6（Redis 8 自建语义缓存：RediSearch KNN + 余弦）+ N1（供应商侧 Context Cache，**2026-08-24 用户裁决收窄为 DeepSeek 侧**）。硬前置 = LT1 压测基线（命中率/延迟降幅的对照面），已于 2026-08-23 通过销账。**依赖裁决（2026-08-24）**：与簇② 批5 用户侧复跑互不依赖——批5 产出只影响簇② 自身收口（观察带门禁定档），簇③ 照常开工，批5 结果后续回传登记。**分支纪律**：簇③ 全部提交落 `phase5-cluster3-semantic-cache` 分支（main 冻结至批5 回传，防用户侧复跑窗口基线漂移）。批次规划（2026-08-24 定案）：批1 缓存核心（存取/失效/指标/配置）→ 批2 `CacheCheckAdvisor` 链序接线（order=460 路由后门控前）+ 事件驱动失效 + 观测 → 批3 N1 DeepSeek 定案 + 设计回写 + E2E 交付（验收：命中率 >30% + 命中延迟 P95 降 >40%，对照 18 §18.4）。**机器侧收口（2026-08-25）**：批1-3 全落地（817 单测绿），用户侧 簇③-E2E 已登记待执行项清单（唯一源），回传后簇③收官 + 分支合并回 main。**E2E 首跑热修（2026-08-25）**：批1 两处缺口实证暴露并修复——坑位㉝（Redisson 4.6.1 `hasIndex` 错误文案匹配表与 Redis Stack "Unknown index name" 不兼容 → 租户索引惰性创建永不执行）+ 坑位㉞（`SemanticCacheProperties` 缺 Bean 注册 → 启用态启动失败）；11 章 v2.74 + 19 章 v2.2；用户侧重建 fat jar 后续跑。**E2E 热修二（2026-08-26）**：续跑——索引创建/命中冒烟/守卫校验全符合预期；失效演练暴露坑位㉟（TAG 反查 `@docIds:{…}` 未转义，UUID 连字符触 TAG 否定符语法 → Syntax error）→ `escapeTagValue` 查询侧转义（存储侧原值不动）；11 章 v2.75 + 19 章 v2.3；全反应器 777 单测绿（+2）。**收官（2026-08-26，用户侧 E2E 通过）**：索引创建/命中冒烟（相似问单帧重放 + 溯源同形）/守卫三项/失效演练闭环（软删 → 删除 1 条 → 重放 miss → 恢复再入）全过，三坑位（㉝/㉞/㉟）修复实证生效。**延迟对照方向性验收**：未命中端到端 5-8s，命中平均 ≈2s，降幅约 60-75% 越过 >40% 线；命中率 >30% 随启用后真实流量观察期读数（`rag.retrieval.cache.hit/miss`）。**⑥ N1 直调对照缓做**（零代码定案不受影响；补测触发 = DeepSeek 控制台缓存读数或主动直调）。**伴生无大碍判定**：日志偶发 `Exception occurred. Channel: … Operation timed out` = 本地开发机公网/家用 NAT 长闲连接静默断链，Redisson 自动重连 + pubsub 自动重订阅自愈（实证异常后 10s 失效消息正常处理），生产 ECS 内网形态无此路径 + TTL 兜底覆盖死窗。**分支合并回 main（2026-08-26，用户裁决提前合入）**：批5 或延迟、开发需继续（簇④）→ 零漂移分析结论先行——缓存族 Bean 全量 `@ConditionalOnProperty` 缺省关 + 零依赖变更 + 全反应器（含 kb-eval 同上下文形态）已绿，`RAG_CACHE_ENABLED=false` 态批5 读数面与合入前逐字节同形（唯一漂移入口 = 执行侧残留启用开关，批5 清单已加守卫行）→ ff 合入（main ea90885→19042d7）。**分支纪律延续**：簇④ 开工按先例新开 `phase5-cluster4-graphrag`，main 冻结至批5 回传不变。

| 项 | 内容 | 状态 |
|---|------|------|
| 开工勘察 | 双路勘察定案：① 代码面——advisor 链空档（护栏簇后/记忆 400/路由 440/Trace 450/门控 500，插入点定 order=460 路由后门控前：路由先分 CHITCHAT 免误命中 + Memory 已跑便多轮检测，命中旁路 Trace+Gate+检索+rerank+生成全套）、失效触发点四处（ETL 成功/Chunk 编辑·恢复/重建终态/重入库蓝绿 + 软删保守失效）、RetrievalContext 租户键、流式短路先例（RetrievalGateAdvisor 直实现 CallAdvisor+StreamAdvisor）、kb-eval 独立装配零影响；② 供应商契约——Redis 8 GA 内建搜索引擎（无需 redis-stack）、Redisson 4.6.1 `RSearch` 类型化 API 源码级核验（`getSearch()`/hnswVector 链/params+dialect(2) KNN 形态/hasIndex 自检）、Spring AI redis 向量模块否决（Jedis 双客户端）、行业基线（企业问答命中率 20-50%，阈值甜点 0.90-0.95） | ✅ 2026-08-24 |
| N1 收窄裁决 | 契约调研实证：百炼 Context Cache 支持清单不含 qwen3 系列（备用/评估链全在清单外）→ 复审定案 N1「百炼半侧」不可行；**用户裁决收窄为 DeepSeek 侧**——实测固定前缀（system+grounding 模板）token 量，≥门槛则自动前缀缓存天然生效核验 usage 遥测（零代码），<门槛实证登记取消；百炼侧登记「待 qwen3 支持」留口 | ✅ 2026-08-24 裁决，批3 落地（E2E 直调对照 2026-08-26 缓做登记，补测触发见收官行） |
| 批1 缓存核心 | `SemanticCacheService`（kb-ai-core/cache）：Redis 8 内建搜索引擎经 Redisson `RSearch` 零新增依赖；每租户一索引域隔离 + KNN top-1 余弦阈值判定（缺省 0.95 保守起步）+ 问句指纹确定性键幂等写入 + 按文档 TAG 反查失效 + 启动期能力探测自关 + 全路径 fail-open；`CacheDocCodec` 自持编解码（实证核验既有编解码器均不适用混合二进制/文本字段）；13.3 预留位启用 `rag.retrieval.cache.hit/miss/invalidated`；`rag.cache.*` 配置族落 application-ai.yml 缺省关（`@ConditionalOnProperty` 整体条件装配）；11 章 v2.71 §11.9 | ✅ 2026-08-24（全反应器 795 单测绿，+18） |
| 批2 Advisor 接线 | `CacheCheckAdvisor` order=460（直实现 CallAdvisor+StreamAdvisor，仿 RetrievalGateAdvisor 短路先例）：命中流式重放（缓存回答单帧 + `CacheTracePayload` 溯源载荷回填 RetrievalContext——Controller TRACE/审计/归档同形消费，[ref-N] 锚定不破）/ 未命中流末聚合异步写入（五重门槛 = rag 链结构性 + 单轮 [USER 消息数] + 非闲聊路由 + 流正常完成 [SUCCESS 操作语义] + final 证据非空；嵌入向量查找-写入单次复用；问句取 InputSanitize 掩码形态）；失效事件接线四处 = kb-api DocumentService ETL COMPLETED 终态帧（覆盖 reparse/replace/重建 [ReindexGateway 委派 reparse 同路径，不重复接线]/首次入库）+ kb-admin ChunkOpsService 编辑/软删/恢复，经 `rag:cache:invalidate` pub/sub 频道（StringCodec + JSON 契约，对齐护栏重载频道先例）；`kb.cache.semantic` 观测 span（cache.outcome 低基数 + cache.tenant 高基数）；`@ConditionalOnProperty` 条件装配 + 消费方 ObjectProvider 容忍（关闭态链形态零变化）；写执行器复用 auditExecutor 虚拟线程；11 章 v2.72 §11.5.1 链序表 + §11.9 批2 段 | ✅ 2026-08-25（全反应器 817 单测绿，+25：资格五闸/命中重放/溯源往返保真/写入门槛/失效频道生命周期/写路径接线） |
| 批3 定案与交付 | N1 DeepSeek 定案（零代码）：官方契约核验（磁盘级前缀缓存默认开、命中 ≈10% 计费、官方门槛 64 tokens / V4-Flash 社区实测 256 待官方确认）+ 主链固定前缀实测（RAG_SYSTEM_PROMPT 73 字符 + GROUNDING 静态头 426 字符 ≈ 499 字符，估算越过双门槛，精确读数经 E2E usage.prompt_tokens）+ 遥测面裁决（Spring AI 标准 Usage 不携 prompt_cache_hit_tokens——应用层零代码不新增映射耦合，观测 = E2E 直调对照 + DeepSeek 控制台）+ 前缀稳定性纪律（Prompt Git Ops 契合；canary 每进程随机重启重建一次；多轮前缀退化为 system 段收益收窄）；百炼侧留口登记；设计回写收口（11 章 v2.73 §11.10 + 13 章 v2.65 §13.3 + 08 章 v2.64 + CLAUDE.md 置换瘦身）；用户侧 E2E 登记清单（簇③-E2E：分支构建启用 → 命中冒烟 + 守卫校验 + 失效演练 + N1 直调对照） | ✅ 2026-08-25（**簇③ 机器侧收口**；零代码变更，817 单测绿基线不变；验收读数待用户侧 E2E 回传） |
| E2E 首跑热修 | 用户侧启用态首跑暴露批1 两处缺口并修复：① **坑位㉝**——Redisson 4.6.1 `hasIndex` 经 Lua 包裹 `FT.INFO` 判存在，仅错误文案匹配 "not found"/"no such index" 判不存在，Redis Stack / RediSearch 对不存在索引返回 "Unknown index name" 文案不匹配即上抛 → 租户索引惰性创建永不执行（查找/写入全程 fail-open 直通）；`indexExists` 按 "unknown index" 文案族补认「不存在」，非文案异常仍上抛外层 fail-open（容错语义不变）；② **坑位㉞**——`SemanticCacheProperties` 仅 `@ConfigurationProperties` 无 Bean 注册（批1 单测手工装配掩盖）→ 启用态启动失败，补 `@Component`（对齐 `ParsingProperties` 先例）。教训登记：条件装配 Bean 启动期装配形态不可由单测手工装配替代核验。11 章 v2.74 + 19 章 v2.2 | ✅ 2026-08-25（全反应器单测绿 +2：文案兜住建索引 / 连接故障不误建；用户侧重建 fat jar + 重启后续跑清单） |
| E2E 热修二 | 续跑索引创建/命中冒烟/守卫校验全符合预期（坑位㉝修复实证生效）；失效演练暴露**坑位㉟**——`invalidateByDocument` TAG 反查 `@docIds:{原始 documentId}` 未转义，RediSearch TAG 表达式内 `-` 为否定符，UUID 连字符必然在场 →「Syntax error at offset N near …」，按文档失效失败回落（TTL 兜底仍生效）；修复 = 查询侧 `escapeTagValue` 对 TAG 特殊字符集逐字符反斜杠转义（存储侧原值不动），批1 断言同步修正；沉淀：RediSearch 查询面仅参数化查询（PARAMS）天然语法安全，手写查询串一律经转义函数。11 章 v2.75 + 19 章 v2.3 | ✅ 2026-08-26（全反应器单测绿 777，+2：UUID 转义行为 / 转义函数契约；用户侧重建续跑失效演练） |
| E2E 验收与收官 | 用户侧全过：索引创建 / 命中冒烟（相似问单帧重放 + 溯源同形）/ 守卫三项（闲聊/多轮/空证据不入缓存）/ 失效演练闭环（软删 → 删除 1 条 → 重放 miss → 恢复再入再命中），三坑位修复实证生效；延迟对照 = 未命中 5-8s vs 命中 ≈2s（降幅约 60-75%，越过 >40% 线方向），命中率 >30% 随真实流量观察期；⑥ N1 直调对照缓做登记（补测触发 = DeepSeek 控制台缓存读数或主动直调）；伴生分析：`Exception occurred. Channel:` 偶发 = 本地开发 NAT 长闲断链，Redisson 自愈无大碍（生产内网形态无此路径 + TTL 兜底） | ✅ 2026-08-26 **簇③收官**（分支已合入 main，批5 零漂移分析先行） |

---

### 簇④ GraphRAG（推进记录）

> 构成：5.1（新 ECS Neo4j Community + 实体关系抽取管道）+ 5.2（三路 RRF 融合 + `rag.graph.enabled` 降级）+ 多跳专项测试集 ≥30 例。验收：多跳准确率 >80% + 抽取抽检 >85% + 三路融合 P95 <600ms + 开关关闭零回归。**开工决策点解除（2026-08-26）**：用户新购第二台 ECS 已装 Neo4j 社区最新版。**分支纪律**：全部提交落 `phase5-cluster4-graphrag`（当日建），main 冻结至簇② 批5 回传不变。**实施基线** = [簇④GraphRAG实施方案（批次推进版）](./sub-cluster-progress/簇④GraphRAG实施方案（批次推进版）.md)（四项定案 + 评审修正 R1-R4 在案）。批次规划（2026-08-26 定案）：批1 核心基建（V2 迁移 + Neo4j 网关 + 抽取管道核心）→ 批2 接线集成（ETL 终态帧触发 + 图检索 + 三路 RRF）→ 批3 生命周期 + 存量回填任务 + 备份脚本 → 批4 多跳测试集 + 评估接线 + 前端/压测扩维 → 批5 收口交付（全模块验证 + E2E 步骤 + 文档回写）。

| 项 | 内容 | 状态 |
|---|------|------|
| 开工勘察 | 三路并行勘察定案：① 检索面——`RrfFusion` 双路硬编码须泛化 N 路、`HybridDocumentRetriever` Future 降级模板可扩三路、条件装配沿用缓存族 `@ConditionalOnProperty`+`ObjectProvider` 先例；② ETL/领域面——`DocumentService.reindexProgressCallback()` COMPLETED 终态帧为抽取触发天然挂接点（缓存失效同位）、限流双先例（Redisson 令牌桶 + JVM Semaphore）、Flyway 下一号 V2、chunk 无 tenant_id 列经 doc JOIN；③ 部署/评估面——`golden/` glob 扫描新投即收纳、Debug 台硬编码四维需扩、场景 A 阈值 500→600、`infra/` 无 NEO4J_* 键族。四项用户定案：原生驱动+自建网关 / 实体向量匹配（检索期零 LLM）/ 多跳集机器侧草稿+用户审定 / 图专项回填任务 | ✅ 2026-08-26 |
| 批1 核心基建 | ✅ 落地五构件：① kb-domain V2 迁移（`kb_document.graph_status`/`graph_updated_at` + `GraphStatus` 四态枚举 [PENDING/EXTRACTING/COMPLETED/FAILED，SKIPPED 裁撤——关闭态恒 PENDING 语义自足] + schema.sql 双源同步，双源守卫表集比对天然兼容）；② kb-infrastructure `graph/` 包——`neo4j-java-driver` 依赖（Boot 4.1 BOM 托管 6.1.0 实证，免版本号）+ `Neo4jProperties`（spring.neo4j.* 落 application-infra.yml）+ `Neo4jConfig` 条件装配（Driver 手工装配 destroyMethod=close，不引 starter——关闭态零副作用）+ `GraphGateway`/`Neo4jGraphGateway`（幂等替换/删除/软删同步/向量检索单管线/计数，全参数化 + 租户 fail-closed + 1024 维向量索引 DDL + 孤儿清扫）+ `GraphSchemaInitializer`（ApplicationReady 幂等，失败不阻断启动）+ `GraphIds`（确定性实体 ID = 租户×规范名×类型）；③ kb-etl 抽取管道——`EntityExtractor`（qwen3.7-plus 手工装配，温度 0 + enable_thinking=false 坑位⑮ 钉死，`.entity()` 结构化，单 chunk 失败返 null 隔离）+ `GraphExtractionService`（窗口语境抽取→确定性 ID 合并→描述嵌入 [维度快失败守卫]→越集关系丢弃→幂等写图→状态机回写；双限流 = Redisson 令牌桶 10/min/租户 [fail-open] + JVM 信号量 3；虚拟线程有界并发同语境增强先例）+ `GraphExtractionListener` SPI（R1 依赖倒置，kb-api 批2 委派指标）+ `GraphExtractionProperties`；④ 配置族 `rag.graph.*`（缺省关）+ `KG_EXTRACTION_PROMPT` 收编 PromptTemplates（4.8 Git Ops）；⑤ 单测 +20（ID 派生 5 / 网关守卫 4 / 抽取解析 3 / 编排语义 8——合并收敛/租户守卫/维度快失败/越集丢弃/软删排除）。实证坑两处登记：Redisson `tryAcquire(long permits, long timeout, TimeUnit)` 首参 long 非 int（javap 核验）；ChatClient `.entity()` 链路要求桩 ChatModel 供非空 `getOptions()`。全反应器 844 单测绿（+20）。**设计回写并批5 收口统一执行**（§12.3 清单，同日连续批次中间稿防返工，偏差留痕于此） | ✅ 2026-08-26 |
| 批2 接线集成 | GraphDocumentRetriever + RrfFusion N 路 + HybridDocumentRetriever 三路 + ETL COMPLETED 帧触发 + `rag.graph.*` 配置族 | 📋 |
| 批3 生命周期与回填 | 文档删除图清理 + chunk 软删同步 + GraphBackfillService（滑动窗口+Redis 任务表）+ neo4j-backup.sh | 📋 |
| 批4 多跳集与评估 | QACategory MULTI_HOP + `--eval.draft-multihop` 草稿工具 + MultiHopMetrics 门禁 + Debug/Chat 扩维 + LoadTestConfig 600ms | 📋 |
| 批5 收口交付 | 全模块单测绿 + E2E 步骤登记（用户侧清单）+ 10/13/17/18 章回写 + CLAUDE.md 同步 | 📋 |
