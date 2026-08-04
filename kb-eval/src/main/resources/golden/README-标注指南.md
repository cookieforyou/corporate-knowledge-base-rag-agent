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
   输出该问题的 Top-10 命中（chunkId + 得分 + 片段）。人工判定哪些 Chunk 确实能回答问题，
   记下其 chunkId。

3. **编写用例**：复制 `corpus-qa.json.example` 为 `corpus-qa.json`，按格式填写：
   - `id`：分类前缀 + 序号（factoid-001 / table-001 / reasoning-001 / multidoc-001）
   - `category`：FACTOID（单文档事实）/ REASONING（跨段推理）/ TABLE（表格数据）/ MULTI_DOC（多文档聚合）/ NEGATIVE（库外问题）
   - `expectedChunkIds`：步骤 2 判定的 Chunk ID 列表（检索指标的度量依据）
   - `expectedAnswer`/`expectedKeywords`：可选，Phase 5 Answer Correctness 指标使用

4. **负向用例**：`negative-out-of-kb.json` 已内置 15 条通用库外问题（即刻可跑）。
   可补充**领域近似但库外**的问题（如知识库无某类产品时问该产品参数），这类最有区分度。

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

# 调并行度（API 限流时下调；网络与配额富余可上调至 8-10）
mvn spring-boot:run -pl kb-eval -Dspring-boot.run.arguments=--eval.concurrency=8

# CI 门禁模式（低于阈值进程非零退出）
mvn spring-boot:run -pl kb-eval -Dspring-boot.run.profiles=ci

# CI 快跑（每类抽样 10 条）
mvn spring-boot:run -pl kb-eval -Dspring-boot.run.profiles=ci -Dspring-boot.run.arguments=--eval.sample-size=10
```
