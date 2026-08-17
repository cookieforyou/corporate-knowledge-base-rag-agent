# Golden Dataset 标注指南

Golden Dataset 是 Phase 2 全部检索/生成验收指标的度量基础（设计文档 16.1）。
本目录 `*.json` 会被 `GoldenDatasetLoader` 自动加载（`.example` 后缀为模板，不加载）。

## 标注工作流

1. **准备语料**：通过前端/上传 API 将待测文档入库（Phase 1 ETL 链路），确认 `kb_chunk` 有数据。

2. **候选 Chunk 发现**（标注辅助器）：
   ```bash
   mvn spring-boot:run -pl kb-eval \
     -Dspring-boot.run.arguments=--eval.annotate-query=你的问题
   ```
   输出该问题的 Top-10 命中（chunkId + 文件名 + 得分 + 片段）。人工判定哪些 Chunk 确实能回答问题，
   记下其 chunkId。

   **全量重标注**（chunk ID 换代后的存量迁移，簇④ A4 修复）：
   ```bash
   mvn spring-boot:run -pl kb-eval -Dspring-boot.run.arguments=--eval.annotate-all
   ```
   对全部正向用例跑 Top-8，落 `target/golden-reannotate-sheet.md`，人工圈定后回填。
   2026-08-12 已完成一轮全量迁移（102 条，80 正向双层锚点）。**圈定口径**（后续
   增量标注与再迁移沿用）：
   - ground truth 以**全库语料**判定，重标注表 Top-8 仅是候选提示——召回度量
     必须独立于检索结果，正确答案不在 Top-8 时照常回填（那正是指标要暴露的漏检）
   - 开放枚举题（「知识库里有哪些…」）取**代表性锚点**：每文档至多一条最直接
     证据 chunk，不穷举实例，保证 Top-K 指标可达性。**细化（2026-08-12 cross-08
     补漏）**：代表性上限仅在枚举实例爆炸（全库证据 > K）时适用；全库证据集
     ≤ K 条时圈全。圈定后以探针实际产出反查「命中但未圈」的有效候选
   - 拆分表（如版面类型表跨 3 chunk）按证据完整性圈全——这是检索真实难点
   - 同一 chunk 可同时锚定多个用例（如 61c58f6c 之于 cross-04 排除条款 /
     cross-08 质保期时长）——按问题语义独立判定，不受已圈用例影响

3. **编写用例**：复制 `corpus-qa.json.example` 为 `corpus-qa.json`，按格式填写：
   - `id`：分类前缀 + 序号（factoid-001 / table-001 / reasoning-001 / multidoc-001）
   - `category`：FACTOID（单文档事实）/ REASONING（跨段推理）/ TABLE（表格数据）/ MULTI_DOC（多文档聚合）/ NEGATIVE（库外问题）
   - `expectedChunkIds`：步骤 2 判定的 Chunk ID 列表（chunk 级检索指标的度量依据；
     chunk ID 为确定性 ID——文档名+序号+增强前原文散列，重入库不变，见设计 9.3 v2.22）
   - `expectedDocs`：期望命中的**文件名**列表（文档级兜底检索指标，设计 16 章 v2.21）——
     匹配探针命中元数据 file_name，跨重入库/解析漂移恒稳定；与 expectedChunkIds 同步填写
   - `expectedAnswer`/`expectedKeywords`：可选，Phase 5 Answer Correctness 指标使用

4. **负向用例**：`negative-out-of-kb.json` 已内置 15 条通用库外问题（即刻可跑）。
   可补充**领域近似但库外**的问题（如知识库无某类产品时问该产品参数），这类最有区分度。
   `boundary-qa.json`（安全簇① v2.43 四批）为**护栏边界问法**专项：库外但贴近护栏误伤域
   的正常业务问句，喂干净集零误伤门禁（cleanRegressionSetHasZeroBlockHits）；扩充此类样本
   须先过内容盲 BLOCK/FLAG 零命中复算（对照 injection-rules.yml 归一化视图），命中即改写
   或触发词项降 FLAG 评估。**挑选口径（v2.43 四批修正实证）**：边界样本必须落在**语料
   未覆盖域**——与语料域重叠的问法会被检索命中并由模型作答，Negative Rejection 判
   PARTIAL/NOT_REJECTED 拖垮鲁棒性门禁（本库已覆盖：安全政策制度/数据治理/K8s 运维/
   产品参数，首批 24 条因此退役 15 条）。覆盖域内的误伤监控改经正向用例标注路径
   （步骤 1-3，带锚点）承接；退役样本寄存 `boundary-qa-bench.json.example`（.example
   不加载，转正向标注或语料演进后复用）。

## 数量与分布目标（16.1）

- 总量 **50+** 条，其中负向用例 **≥ 20%**
- 分类覆盖：FACTOID ≥ 40%，TABLE ≥ 15%（表格是本项目核心场景），REASONING/MULTI_DOC 各 ≥ 10%
- 专有名词专项：标题词/字段名/产品型号类问题 ≥ 10 条（对应验收指标「专有名词命中率 > 90%」）

## 指标联动说明

- `expectedChunkIds` 为空的用例：跳过 Top-K Recall / MRR / Context Precision，仅评生成侧（Faithfulness / Response Relevancy）
- `category = NEGATIVE`：走 Negative Rejection 判定（REJECTED / PARTIAL / NOT_REJECTED），不评 Faithfulness
- 门禁阈值见 `application.yml` 的 `eval.thresholds`（对应 16.4 阈值表）

## 运行评估

```bash
# 全量评估（需 ECS 基础设施 + DEEPSEEK_API_KEY + DASHSCOPE_API_KEY 环境变量）
# 用例按 eval.concurrency（默认 5）虚拟线程并行，74 条约 10-15 分钟
mvn spring-boot:run -pl kb-eval

# 检索-only 快跑：只评 Recall/MRR/Context Precision，跳过生成与 Judge——
# 标注核验/检索回归用，秒级完成，不依赖 DASHSCOPE_API_KEY
mvn spring-boot:run -pl kb-eval -Dspring-boot.run.arguments=--eval.retrieval-only=true

# chain 探针（簇① A1）：走完整 advisor 链（改写→[扩展]→双路→RRF→重排）度量检索产出，
# 是评估改写/扩展等前置组件收益的唯一探针（默认 hybrid 探针只测检索器本体）。
# 每用例含一次生成调用（答案丢弃取 trace），比 hybrid 慢；须设语料租户（fail-closed 适配）。
EVAL_PROBE=chain EVAL_TENANT_ID=tenant_001 mvn spring-boot:run -pl kb-eval \
  -Dspring-boot.run.arguments=--eval.retrieval-only=true

# 调并行度（API 限流时下调；网络与配额富余可上调至 8-10）
mvn spring-boot:run -pl kb-eval -Dspring-boot.run.arguments=--eval.concurrency=8

# CI 门禁模式（低于阈值进程非零退出）
mvn spring-boot:run -pl kb-eval -Dspring-boot.run.profiles=ci

# CI 快跑（每类抽样 10 条）
mvn spring-boot:run -pl kb-eval -Dspring-boot.run.profiles=ci -Dspring-boot.run.arguments=--eval.sample-size=10
```
