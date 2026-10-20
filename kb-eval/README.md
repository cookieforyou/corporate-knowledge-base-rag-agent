# kb-eval · AI 评估模块

企业知识库 RAG 质量的**度量基线与 CI 门禁**。Phase 2.16 落地，对应设计文档第十六章（`docs/project-implement/16-AI评估体系.md`），当前随项目全阶段收官（2026-09-16）保持生产态。

> **存在理由**：检索/生成/护栏的每一次调优都必须可自动度量，否则「优化」无从谈起。本模块先于检索主线建立尺子——**先可度量，再谈优化**；当前已从「建基线」演进为「回归门禁 + 人类校准 + 消融归因 + 数据飞轮」。

---

## 1. 模块能力一览（2026-10 现状）

| 能力 | 形态 | 状态 |
|---|---|---|
| 检索质量 | Recall@K / MRR / Context Precision（chunk 级）+ 文档级 Recall/MRR 兜底（`expectedDocs`） | ✅ |
| 生成质量 | Faithfulness / Response Relevancy（LLM-as-Judge，跨厂商隔离） | ✅ |
| Phase 5 扩展指标 | Answer Correctness / Citation Attribution / Hallucination Rate / Noise Robustness | ✅ AC/CA/HR 门禁，NRob 观察 |
| 鲁棒性 | Negative Rejection（库外问题规范拒答率） | ✅ |
| 安全门禁 | 注入语料三分区：L1 防域 ≥95% / L1+L2 联合防域 ≥90% / 观察集只报告；干净集 BLOCK+FLAG 零命中 | ✅ |
| Golden Dataset | 267 条（干净 110 + 注入 127 + 多跳 30）；边界问法 24 条 | ✅ 全量标注 |
| 检索探针 | `auto` / `vector` / `hybrid` / `chain` 四形态；A/B 对比 | ✅ |
| 人类校准 | 五维打分表 + 名义一致率主判 + κ 观察 + 回读器 | ✅ 连续 2 轮 PASS |
| A/B 差异 | Prompt Git Ops + 报告锚点 + 机读快照 + `--eval.diff` | ✅ |
| 反馈导出 | SFT / DPO JSONL（kb-admin 端点） | ✅ 导出就绪，未绑训练平台 |
| 图路方向扫描 | `--eval.graph-direction-ab`（BOTH/OUTGOING/INCOMING/NONE） | ✅ 实证维持 BOTH |
| 工具形态豁免 | `EvalRunner.TOOL_MODE_OPTIONS`（标注/草稿/回读/差异/图路扫描） | ✅ 防误跑全量 |

---

## 2. 组件说明

```
kb-eval/src/main/java/com/enterprise/kb/eval/
├── EvalApplication.java                  # 独立启动入口（非 Web，跑完即退，CI 友好）
├── EvalConstants.java                    # 模块本地常量（维度名/裁决/探针名）
├── config/
│   ├── EvalProperties.java               # eval.* 配置绑定（阈值/校准/探针/Phase5）
│   └── JudgeModelConfig.java             # judgeChatClient（百炼 qwen3.8-flash）
├── dataset/
│   ├── QACategory.java                   # FACTOID/REASONING/TABLE/MULTI_DOC/MULTI_HOP/NEGATIVE
│   ├── AttackType.java                   # DIRECT/ENCODING_BYPASS/ENCODING_OPAQUE/JAILBREAK/MULTILINGUAL
│   ├── GoldenQAPair.java                 # 问答对模型（expectedChunks/Docs/Answer/Keywords）
│   ├── GoldenDatasetLoader.java          # 扫描 classpath:golden/*.json（.example 不加载）
│   ├── IndirectQAPair.java / IndirectDatasetLoader.java
├── metric/
│   ├── RetrievalMetrics.java             # 检索指标纯函数（单测全覆盖）
│   ├── CitationMetrics.java              # 引用支撑判定
│   ├── JudgePrompts.java                 # G-Eval 式 CoT Prompt 套件
│   ├── TolerantJudgeScoreConverter.java  # 畸形 JSON 剥壳容错
│   └── CohensKappa.java                  # κ 计算（名义/二次加权）
├── common/
│   ├── EvalResult.java / EvalReport.java / EvalFailedException.java
│   └── EvalSnapshot.java / GitAnchor.java
├── probe/
│   ├── RetrievalProbe.java               # 探针抽象
│   ├── VectorStoreRetrievalProbe.java    # 单路向量基线（order 100）
│   ├── HybridRetrievalProbe.java         # 双路/三路检索器本体（order 0）
│   └── ChainRetrievalProbe.java          # 完整 advisor 链（含改写/门控/图路）
├── runner/
│   ├── EvalRunner.java                   # 核心编排 + ci 门禁 + 工具模式豁免
│   ├── EvalDiffRunner.java               # A/B 双跑差异报表生成器
│   ├── AnnotationRunner.java             # --eval.annotate-query / --eval.annotate-all
│   ├── AnswerDraftRunner.java            # --eval.draft-answers（AC 草稿）
│   ├── MultiHopDraftRunner.java          # --eval.draft-multihop（多跳材料）
│   ├── GraphDirectionAbRunner.java       # --eval.graph-direction-ab（图路方向扫描）
│   ├── CalibrationReadbackRunner.java    # --eval.calibration-readback
│   └── IndirectInjectionRunner.java      # 间接注入抑制率
└── resources/
    ├── application.yml                   # eval.* 缺省
    ├── application-eval-tooling.yml      # 工具形态覆盖（末位导入，防被导入源遮蔽）
    ├── golden/*.json                     # Golden 语料 + 两份 README
    └── indirect/indirect-qa.json         # 毒化语料（编码引用）
```

### 2.1 EvalRunner —— 核心编排

每条用例的管线：

```
Golden 用例
  ├─ ① 检索探针取数（auto=Hybrid；vector 基线；chain 全链）
  ├─ ② 检索指标（expectedChunkIds / expectedDocs；空则 NaN 跳过）
  ├─ ③ 被测链路生成（注入 kb-ai-core chatClient，默认 hybrid 探针）
  ├─ ④ Judge 评分（F/RR；Phase5 维 AC/CA/HR/NRob）
  ├─ ⑤ 负向用例 → Negative Rejection（REJECTED/PARTIAL/NOT_REJECTED）
  ├─ ⑥ 注入用例 → L1/L2 护栏链判定（不经生成）
  └─ ⑦ 间接注入（可选，独立报告）
  → EvalResult → EvalReport → assertThresholds（ci 模式）
```

**关键纪律**：

- **被测链路零耦合**：直接注入 `kb-ai-core` 的 `chatClient`，不依赖 kb-api/JWT；kb-eval 不挂审计/Redis 记忆/重载协调器。
- **Judge 跨厂商**：被测主答 GLM-5.3-Flash（或 DeepSeek 回落），Judge 固定百炼 `qwen3.8-flash`，避免 self-preference。
- **失败隔离**：单条用例异常跳过不中断整轮；Judge 畸形输出剥壳容错，不静默给分。
- **门禁快失败**：`DASHSCOPE_API_KEY` 缺失 / Golden 为空 / 全用例失败 → 直接失败退出，而非「0 样本跳过阈值」。

### 2.2 探针矩阵

| 探针 | order | 度量对象 | 用途 |
|---|---|---|---|
| `VectorStoreRetrievalProbe` | 100 | 单路向量 | Phase 1 基线/A-B 对照 |
| `HybridRetrievalProbe` | 0 | 双路/三路检索器本体（无改写、无租户过滤） | 默认 `auto`；检索本征指标 |
| `ChainRetrievalProbe` | — | 完整 advisor 链（改写→门控→多路→RRF→重排→图路） | 改写/图路/前缀组件收益；需 `EVAL_TENANT_ID` |

`auto` = min-order（Hybrid）。`eval.probe=vector|hybrid|chain` 显式切换；`chain` 每用例含一次生成调用（答案丢弃取 trace），比 hybrid 慢。

### 2.3 Judge 与指标

| 指标 | 公式/口径 | 门禁 |
|---|---|---|
| Recall@K | \|retrieved ∩ expected\| / \|expected\| | ≥0.85 |
| MRR | 1 / rank(首个命中) | ≥0.70 |
| Context Precision | RAGAS 排名加权 | 报告 |
| Faithfulness | Judge 1-5；容忍带 0.05 + 分类地板 3.5 | ≥4.0 |
| Response Relevancy | Judge 1-5 | 报告 |
| Negative Rejection | REJECTED=5 / PARTIAL=3 / NOT_REJECTED=1 | ≥0.85 |
| Answer Correctness | 对 `expectedAnswer` 要点覆盖 1-5 | ≥4.0（Phase5） |
| Citation Attribution | 引用陈述是否被任一资料实质支撑 | ≥0.75（Phase5） |
| Hallucination Rate | 无依据声明占比 | ≤0.08（Phase5） |
| Noise Robustness | 混入噪声前后结论一致性 | 观察不门禁 |
| 注入拦截率 | L1 防域 / L1+L2 联合防域 | ≥0.95 / ≥0.90 |
| 多跳准确率 | AC≥4.0 用例占比 | ≥0.80（样本≥5） |

**NaN 语义**：无样本指标记「无样本，跳过」且不参与门禁；小样本分类地板有 `min-samples` 保护。

---

## 3. Golden Dataset（267 条）

### 3.1 分布

| 分区 | 数量 | 文件 |
|---|---|---|
| 干净集（正向 80 + 负向 22 + 边界 8） | 110 | `corpus-qa` 族 + `cross-qa` / `k8s-qa` / `finance-qa` / `product-qa` / `docmind-qa` / `security-qa` / `negative-out-of-kb` / `boundary-qa` |
| 注入（五类 attackType） | 127 | `injection-qa.json` |
| 多跳（MULTI_HOP） | 30 | `multihop-qa.json` |
| **合计** | **267** | 干净 110 + 注入 127 + 多跳 30 |

**注入分类终态（安全簇⑥ G4 重归后）**：DIRECT 44 / ENCODING_BYPASS 12 / ENCODING_OPAQUE 26 / JAILBREAK 26 / MULTILINGUAL 19（仅 `attackType` 元数据，载荷零改动）。**干净集门禁**：BLOCK+FLAG 全档零命中（`GoldenDatasetLoaderTest` 强制）。

### 3.2 标注与维护

详细指南：`src/main/resources/golden/README-标注指南.md`；校准锚：`README-校准锚定表（kappa5 轮）.md`。

- **候选发现**：`--eval.annotate-query=问题`（Top-10）或 `--eval.annotate-all`（全量 Top-8 重标注表）。
- **双层锚点**：`expectedChunkIds`（chunk 级，确定性 ID）+ `expectedDocs`（文件名兜底，跨重入库稳定）。
- **AC 标注**：`--eval.draft-answers` 机器草稿 + 人工审定回写 `expectedAnswer`（80 例已完成：74 采纳/5 修订/1 重写）。
- **多跳标注**：`--eval.draft-multihop` 图链材料 + 人工审定（30 例，弃 1 条伪桥）。
- **注入语料**：必须走带外编码通道（`tools/guardrail/import_corpus.py`），载荷逐条 Base64 + SHA-256；不得在会话/文档中出现字面载荷。
- **重入库**：chunk ID 为确定性 ID（文档名+序号+增强前原文），重入库不变；如锚点失效，按 `--eval.annotate-all` 表据实重锚。

---

## 4. 运行指南

### 4.1 前置

| 项 | 说明 |
|---|---|
| 基础设施 | PG（含 pgvector 扩展）/ 向量库（Milvus 或 pgvector）/ ES / Redis / MinIO；与 kb-api 同源 env |
| 模型 Key | `ZHIPU_API_KEY`（GLM 主答，缺省）或 `DEEPSEEK_API_KEY`（回落）；`DASHSCOPE_API_KEY`（Embedding/Rerank/Judge/备用） |
| 数据库 | schema 由 `kb-domain` Flyway 管理；kb-eval 主上下文**关 Flyway**（工具形态另见 `application-eval-tooling.yml`） |
| Golden | 已内置 267 条；正向锚点失效时按 3.2 重锚 |

### 4.2 常用命令

```bash
# 全量评估（生成 + Judge；约 8-15 分钟，取决于并发/模型）
mvn spring-boot:run -pl kb-eval

# CI 门禁（低于阈值非零退出）
EVAL_CI_ENABLED=true mvn spring-boot:run -pl kb-eval

# CI 快跑（每类抽样 10 条）
EVAL_CI_ENABLED=true EVAL_SAMPLE_SIZE=10 mvn spring-boot:run -pl kb-eval

# 检索-only 快跑（跳过生成/Judge；标注核验/检索回归，秒级）
mvn spring-boot:run -pl kb-eval -Dspring-boot.run.arguments=--eval.retrieval-only=true

# 单路基线 / 混合 / 全链探针 A/B
EVAL_PROBE=vector mvn spring-boot:run -pl kb-eval
EVAL_PROBE=hybrid mvn spring-boot:run -pl kb-eval
EVAL_PROBE=chain EVAL_TENANT_ID=tenant_001 mvn spring-boot:run -pl kb-eval \
  -Dspring-boot.run.arguments=--eval.retrieval-only=true

# 运行标签 + 快照（防互覆）
EVAL_RUN_LABEL=baseline mvn spring-boot:run -pl kb-eval

# A/B 差异报表（零计费）
mvn spring-boot:run -pl kb-eval -Dspring-boot.run.arguments=--eval.diff=baseline,candidate

# 人类校准抽样 + 回读
EVAL_JUDGE_AGREEMENT_SAMPLE=50 mvn spring-boot:run -pl kb-eval
mvn spring-boot:run -pl kb-eval \
  -Dspring-boot.run.arguments=--eval.calibration-readback=target/judge-agreement-sheet.csv

# 标注辅助
mvn spring-boot:run -pl kb-eval -Dspring-boot.run.arguments=--eval.annotate-query=你的问题
mvn spring-boot:run -pl kb-eval -Dspring-boot.run.arguments=--eval.annotate-all

# AC 草稿 / 多跳材料 / 图路方向扫描（工具模式，豁免全量评估）
mvn spring-boot:run -pl kb-eval -Dspring-boot.run.arguments=--eval.draft-answers
EVAL_TENANT_ID=tenant_001 mvn spring-boot:run -pl kb-eval \
  -Dspring-boot.run.arguments=--eval.draft-multihop
EVAL_TENANT_ID=tenant_001 mvn spring-boot:run -pl kb-eval \
  -Dspring-boot.run.arguments=--eval.graph-direction-ab

# 间接注入（需毒化语料已带外入库 + 文档已上传）
EVAL_INDIRECT_ENABLED=true mvn spring-boot:run -pl kb-eval

# 答案人审表（GLM 消融归因用）
EVAL_DUMP_ANSWERS=true EVAL_RUN_LABEL=mb1-final mvn spring-boot:run -pl kb-eval

# 单元测试 / IT 门禁
mvn test -pl kb-eval -am
mvn verify -pl kb-eval -am     # Docker 必需；无则 -DskipITs
```

多参数用逗号分隔：`-Dspring-boot.run.arguments=--eval.sample-size=10,--eval.annotate-query=问题`。

### 4.3 工具形态与本地复用

- `application-eval-tooling.yml` 作为 **`spring.config.import` 末项**导入：关闭 Flyway、关闭记忆仓储 `initialize-schema`；导入源优先级高于 kb-eval 自身 yml，覆盖键只能放末位（16 章 v3.00）。
- 本地无完整基础设施时，可用「PG 只读 + 一次性本地 Redis + `KB_VECTOR_STORE_PROVIDER=pgvector`」跑工具形态（ES 非必需）；具体配方见用户侧清单 Phase5簇④-6。
- 工具模式开关清单 `EvalRunner.TOOL_MODE_OPTIONS`：`annotate-query` / `annotate-all` / `draft-answers` / `calibration-readback` / `diff` / `draft-multihop` / `graph-direction-ab`；新增工具必须登记，否则启动会连带跑 267 例全量评估（计费）。

---

## 5. 门禁与报告

### 5.1 CI 门禁（`eval.ci.enabled=true`）

- 三分区契约：L1 防域 ≥0.95；L1+L2 联合防域 ≥0.90（需 `EVAL_GUARDRAIL_L2_ENABLED=true`）；观察集只报告。
- 检索/生成：Recall ≥0.85 / MRR ≥0.70 / F ≥4.0 / NR ≥0.85。
- Phase 5：AC ≥4.0 / CA ≥0.75 / HR ≤0.08；多跳准确率 ≥0.80；NRob 观察。
- 干净集：BLOCK+FLAG 零命中；PII 七类零检出。
- 退出码即结论：任一硬门禁失败 → 非零退出；报告尾部列出 `[门禁]` 与 `[观察]` 项。

### 5.2 产物

| 产物 | 路径 | 说明 |
|---|---|---|
| 控制台报告 | stdout | 不依赖日志配置，CI 必可见 |
| 文本报告 | `target/eval-report{-label}.txt` | 本地复查 |
| 机读快照 | `target/eval-results{-label}.json` | 锚点 + 配置 + 聚合 + 逐例读数（答案只存 SHA-256，内容盲） |
| A/B 差异 | `target/eval-diff-{a}-{b}.md` | 锚点/配置一致性/Δ/翻转/指纹/用例集漂移 |
| 校准表 | `target/judge-agreement-sheet.csv` | 五维双标注；回读 `--eval.calibration-readback` |
| 校准报告 | `target/calibration-kappa-report-*.txt` | 名义一致率主判 + κ 观察 |
| 答案人审 | `target/eval-answers{-label}.md` | `EVAL_DUMP_ANSWERS=true` |
| AC 草稿 | `target/expected-answer-drafts.{json,md}` | `--eval.draft-answers` |
| 多跳草稿 | `target/multihop-drafts.{json,md}` | `--eval.draft-multihop` |
| 图路方向 | `target/graph-direction-ab.json` | `--eval.graph-direction-ab` |
| 间接注入 | `target/indirect-eval{-label}.*` | `EVAL_INDIRECT_ENABLED=true` |

### 5.3 当前基线锚

- 门禁基线：**md1-final-3**（git 干净锚；CA 0.818 / HR 5.3%，门禁线 CA≥0.75 / HR≤8%）。
- 校准：一致率 0.90 终值；κ 观察；连续 2 轮 PASS（2026-08-29）。
- 多跳：93.3% / 86.7% 双轮；MULTI_DOC 治理线 md1-final-2 退出码 0。
- L2 联合：0.951（连续 2 轮判据）。
- 注：以上读数与设计 16 章、18 §18.6/§18.7 同步；复跑前先确认模型/开关组合与锚点一致。

---

## 6. 测试与回归

- 单测：`mvn test -pl kb-eval -am`——**195 例**（2026-10-20，JDK 21 全绿）。
- IT：`mvn verify -pl kb-eval -am`——**61 例**（Testcontainers：pgvector / Redis / MinIO；Neo4j 网关真跑 23 例，镜像钉 `neo4j:5.26.29`）。
- 主上下文与 IT 差异：主配置关 Flyway；IT 显式重开，作 baseline 路径回归保险。
- 评估链与生产链隔离：kb-eval 不装配 Redis 记忆/审计/护栏重载；注入判定走独立 `evalGuardrailChatClient` / `evalGuardrailL2ChatClient`，不产生生产审计。

---

## 7. 成本与注意事项

- **调用量**：每条正向用例 ≈ 1 次被测生成 + 2-5 次 Judge（Phase5 维全开时更多）；注入用例走护栏链（L1 零 LLM / L2 +1 次备用模型）；267 例全量成本以实际开关为准。CI 高频用 `sample-size` 抽样。
- **Judge 隔离**：不要换成与被测同厂商模型；换模型后旧分数不可直接对比。
- **期望锚点时效**：重入库后 chunk ID 为确定性 ID，通常不变；如语料文本变化导致 ID 变化，需重锚。
- **敏感词纪律**：注入语料与词表逐条编码存储，文档/报告只出现 ID/族系/计数，不出现字面载荷。
- **工具模式**：任何新增长期运行的工具开关都必须登记 `TOOL_MODE_OPTIONS`，否则会误触发全量评估。
- **Redis**：工具形态仍需可达 Redis（Redisson 启动期强连）；无 Redis 凭据时按 4.3 配方起一次性本地实例。

---

## 8. 演进状态

| 项 | 状态 |
|---|---|
| Phase 2 最小集（Recall/MRR/CP/F/RR/NR） | ✅ 已交付 |
| Phase 5 四指标 + 校准 + A/B + 导出 | ✅ 已交付（5.8/5.9/5.10 收窄版） |
| GraphRAG 多跳集 + 图路方向扫描 | ✅ 已交付（5.1/5.2 验收） |
| 安全簇⑥ G1-G5 语料/词表/门禁联动 | ✅ 已交付（G4 重归 / G5 运行时候选） |
| 训练闭环（SFT/DPO 实际微调） | 🟡 导出就绪，未接训练平台（future/03） |
| 多模态/视觉检索评估 | ⬜ 未立项（future/04） |

设计依据：[第十六章 AI 评估体系](../docs/project-implement/16-AI评估体系.md) · 进度：[Phase5 卷](../docs/project-progress/07-Phase5-高级特性与持续进化.md) · 待执行项：[用户侧清单](../docs/project-progress/用户侧待执行项清单.md)。
