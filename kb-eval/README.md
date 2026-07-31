# kb-eval · AI 评估模块

企业知识库 RAG 质量的**度量基线与 CI 门禁**。Phase 2.16 落地，对应设计文档第十六章（`docs/project-implement/16-AI评估体系.md`）。

> **存在理由**：Phase 2 的全部验收指标（混合检索 Top-5 命中率 > 85%、专有名词命中率 > 90%、负向拒绝率……）都必须可自动度量，否则"调优"无从谈起。本模块先于检索主线建立尺子——**先可度量，再谈优化**。

---

## 1. 模块能力一览

| 能力 | 形态 | 状态 |
|---|---|---|
| 检索质量度量 | Recall@K / MRR / Context Precision（RAGAS 排名加权） | ✅ |
| 生成质量度量 | Faithfulness / Response Relevancy（LLM-as-Judge，跨厂商隔离） | ✅ |
| 鲁棒性度量 | Negative Rejection（库外问题规范拒答率） | ✅ |
| Golden Dataset | JSON 数据集（Git 版本化）+ 标注辅助工具 + 标注指南 | ✅ 框架就绪，语料待标注 |
| CI 门禁 | ci profile 启动即跑，低于阈值进程非零退出 | ✅ |
| 检索探针演进 | 单路基线 → 混合检索探针自动替换（order 机制） | ✅ 机制就绪 |
| Answer Correctness / Citation Attribution / Noise Robustness | 依赖 expectedAnswer 标注与引用链路 | ⬜ Phase 5 |
| Judge 人类校准 / 基线回归对比 | 一致率校准、Langfuse dataset run | ⬜ Phase 5 |

---

## 2. 组件说明

```
kb-eval/src/main/java/com/enterprise/kb/eval/
├── EvalApplication.java              # 独立启动入口（非 Web，跑完即退，CI 友好）
├── config/
│   ├── EvalProperties.java           # eval.* 配置绑定（阈值表、Judge、抽样）
│   └── JudgeModelConfig.java         # judgeChatClient Bean（百炼 qwen3.7-plus，跨厂商评判）
├── dataset/
│   ├── QACategory.java               # 用例分类枚举（FACTOID/REASONING/TABLE/MULTI_DOC/NEGATIVE）
│   ├── GoldenQAPair.java             # 问答对模型（可选字段与指标联动）
│   └── GoldenDatasetLoader.java      # 扫描 classpath:golden/*.json（.example 天然排除）
├── metric/
│   ├── RetrievalMetrics.java         # 检索指标纯函数（无外部依赖，单测全覆盖）
│   └── JudgePrompts.java             # G-Eval 式 CoT 评分 Prompt + JudgeScore 结构
└── runner/
    ├── RetrievalProbe.java           # 检索探针抽象（评估器 ↔ 被测检索链路的解耦点）
    ├── VectorStoreRetrievalProbe.java# 当前默认：单路向量检索（Phase 1 基线）
    ├── EvalResult.java               # 单条用例结果
    ├── EvalReport.java               # 聚合报告 + 门禁判定 + 文本摘要
    ├── EvalFailedException.java      # 门禁失败异常（CI 非零退出）
    ├── EvalRunner.java               # 核心编排 + ci 门禁入口
    └── AnnotationRunner.java         # 标注辅助器（--eval.annotate-query）
```

### 2.1 EvalRunner —— 核心编排

每条 Golden 用例的处理管线：

```
Golden 用例
  ├─ ① 检索探针取数（RetrievalProbe.probe，Top-K）
  ├─ ② 检索指标（expectedChunkIds 为空 → NaN，聚合时跳过）
  ├─ ③ 被测链路生成（注入 kb-ai-core 的 chatClient Bean）
  └─ ④ Judge 评分
       ├─ 负向用例 → Negative Rejection 判定（REJECTED/PARTIAL/NOT_REJECTED）
       └─ 正向用例 → Faithfulness + Response Relevancy
  → EvalResult → 聚合 EvalReport → assertThresholds（ci 模式）
```

关键设计：

- **被测链路零耦合**：直接注入 kb-ai-core 的 `chatClient` Bean。Phase 1 形态是 `QuestionAnswerAdvisor`；Phase 2.10 替换为 `RetrievalAugmentationAdvisor` 后，**本模块无需任何改动**即自动度量新链路。
- **CI 语义天然**：非 Web 应用，评估跑完进程退出——成功 exit 0，门禁失败抛 `EvalFailedException` exit 非 0。
- **失败隔离**：单条用例异常（API 超时等）记录日志后跳过，不中断整轮评估。

### 2.2 RetrievalProbe —— 可演进的检索探针

评估器不直接依赖具体检索实现，而是经 `RetrievalProbe` 抽象取数，按 Spring `Ordered` 接口选择 order 最小的 Bean：

| 阶段 | 探针 | order | 度量对象 |
|---|---|---|---|
| 2.6 之前 | `VectorStoreRetrievalProbe` | 100 | Phase 1 单路向量检索基线 |
| 簇 B 落地后（当前） | `HybridRetrievalProbe` | 0 | 混合检索全链路（向量+BM25+RRF） |

混合探针（order=0）就位后自动顶替单路探针（`VectorStoreRetrievalProbe` 经 `@ConditionalOnMissingBean` 自动退让）——**评估器与数据集零改动，评估结果自动切换为混合检索的度量**。

**A/B 基线对比**：`eval.probe` 显式指定探针（`auto` 默认 / `vector` / `hybrid`），对比 Phase 1 单路基线与混合检索的收益：

```bash
mvn spring-boot:run -pl kb-eval -Dspring-boot.run.arguments=--eval.probe=vector  # 单路基线
cp kb-eval/target/eval-report.txt baseline-vector.txt                            # 留存（每次运行覆盖）
mvn spring-boot:run -pl kb-eval                                                  # 混合检索（auto）
```

探针实现注意：`VectorStoreRetrievalProbe` 使用 `similarityThreshold=0.0`（评估期不做阈值过滤，完整观测排序分布）；chunkId 取 `metadata.chunk_id`（回退 `Document.id`，与 ETL 的 `Document.id = kb_chunk.id` 不变量一致）。

### 2.3 LLM-as-Judge —— 跨厂商隔离

- **被测**：DeepSeek V4（kb-ai-core 链路）
- **Judge**：默认百炼 **qwen3.7-plus**（经 `DASHSCOPE_API_KEY` 复用现有密钥，无需新增）

跨厂商评判规避 self-preference 偏差（设计文档 16.3）。Judge Prompt 采用 G-Eval 式 CoT（先推理后打分，缓解长度偏差），结构化输出经 `ChatClient.entity()` 映射为 `JudgeScore(score, reason, verdict)`。

Judge 模型可换：`EVAL_JUDGE_MODEL` 环境变量（如 qwen3.7-max 效果更好）。16.3 的完整校准（与人工标注 85-90% 一致率）是 Phase 5 事项。

### 2.4 指标定义

| 指标 | 公式 | 说明 |
|---|---|---|
| **Recall@K** | \|retrieved ∩ expected\| / \|expected\| | 期望 Chunk 在 Top-K 中的命中比例 |
| **MRR** | 1 / rank(首个命中) | 首个相关结果的倒数排名（无命中 = 0） |
| **Context Precision** | Σ(precision@k × rel(k)) / \|expected\| | RAGAS 排名加权形态：相关结果排名越靠前得分越高 |
| **Faithfulness** | Judge 1-5 分 | 回答是否忠于检索上下文（无幻觉/无外部知识混入） |
| **Response Relevancy** | Judge 1-5 分 | 回答是否切题、完整、无冗余 |
| **Negative Rejection** | REJECTED 占比 | 库外问题规范拒答率（REJECTED=5 / PARTIAL=3 / NOT_REJECTED=1） |

**NaN 语义**：`expectedChunkIds` 为空的用例不参与检索侧指标聚合；某指标样本数为 0 时报告记"无样本，跳过"且**不参与门禁**（建基线期策略——语料标注未完成时不会误杀 CI）。

---

## 3. Golden Dataset

### 3.1 数据集位置与加载

- 路径：`src/main/resources/golden/*.json`（随模块打包，Git 版本化）
- 加载：`GoldenDatasetLoader` 启动时扫描全部 `*.json`；`*.json.example` 为模板，不被加载
- 当前内容：
  - `negative-out-of-kb.json` — 15 条通用库外问题（**开箱即用**，Negative Rejection 基线）
  - `corpus-qa.json.example` — 语料相关问答模板（复制为 `.json` 后填写）

### 3.2 用例格式

```json
{
  "id": "factoid-001",
  "category": "FACTOID",
  "question": "增值税发票的认证期限是多少天？",
  "expectedKeywords": "认证,期限",
  "expectedAnswer": "增值税专用发票应在开具之日起 360 日内完成认证。",
  "expectedChunkIds": ["a1b2c3d4-...", "e5f6g7h8-..."]
}
```

| 字段 | 必填 | 用途 |
|---|---|---|
| `id` | ✅ | 唯一标识（建议分类前缀+序号） |
| `category` | ✅ | FACTOID / REASONING / TABLE / MULTI_DOC / NEGATIVE |
| `question` | ✅ | 测试问题 |
| `expectedKeywords` | ⬜ | Phase 5 Answer Correctness 宽松匹配 |
| `expectedAnswer` | ⬜ | Phase 5 Judge 严格评分 |
| `expectedChunkIds` | ⬜（正向用例强烈建议） | 检索指标度量依据；为空则该条仅评生成侧 |

### 3.3 数量与分布目标（设计文档 16.1）

- 总量 **50+**，负向用例 **≥ 20%**
- FACTOID ≥ 40%；TABLE ≥ 15%（表格是本项目核心场景）；REASONING / MULTI_DOC 各 ≥ 10%
- 专有名词专项（标题词/字段名/产品型号）≥ 10 条——对应验收指标「专有名词命中率 > 90%」

### 3.4 标注工作流

```
① 语料入库（前端上传 → Phase 1 ETL → kb_chunk 有数据）
        ↓
② 候选 Chunk 发现（标注辅助器，对每个待测问题跑一遍）
   mvn spring-boot:run -pl kb-eval \
     -Dspring-boot.run.arguments=--eval.annotate-query=增值税发票认证期限
   → 输出 Top-10 命中：chunkId + 得分 + 内容片段
        ↓
③ 人工判定：哪些 Chunk 确实能回答问题 → 记入 expectedChunkIds
        ↓
④ 复制 corpus-qa.json.example → corpus-qa.json，填写用例
        ↓
⑤ 跑评估验证（见第 4 节）
```

详细指南：`src/main/resources/golden/README-标注指南.md`

---

## 4. 操作步骤

### 4.1 前置条件

| 项 | 说明 |
|---|---|
| ECS 基础设施 | 向量库（Milvus/pgvector）+ PostgreSQL 可达（环境变量 `DB_URL`、`KB_VECTOR_STORE_PROVIDER` / `KB_MILVUS_*` 等，与 kb-api 启动变量同源） |
| `DEEPSEEK_API_KEY` | 被测链路 LLM |
| `DASHSCOPE_API_KEY` | Judge 模型（qwen3.7-plus） |
| 数据库 schema | 已执行 `kb-domain/src/main/resources/schema.sql`（应用以 `ddl-auto: validate` 启动） |
| Golden 语料 | 负向用例已内置；正向用例按 3.4 标注（未标注也可跑，检索/生成指标显示"无样本，跳过"） |

### 4.2 运行模式

```bash
# ① CI 门禁模式：低于阈值进程非零退出（CI 流水线用）
mvn spring-boot:run -pl kb-eval -Dspring-boot.run.profiles=ci

# ② 手动全量评估：只出报告，不做门禁判定
mvn spring-boot:run -pl kb-eval

# ③ CI 快跑：每类抽样 10 条（控成本/冒烟用）
mvn spring-boot:run -pl kb-eval -Dspring-boot.run.profiles=ci \
  -Dspring-boot.run.arguments=--eval.sample-size=10

# ④ 标注辅助：输出某问题的 Top-10 候选 Chunk（供人工判定 expectedChunkIds）
mvn spring-boot:run -pl kb-eval \
  -Dspring-boot.run.arguments=--eval.annotate-query=你的问题

# ⑤ 单元测试（指标纯函数，无外部依赖，常驻 CI）
mvn test -pl kb-eval -am

# ⑥ 集成测试（依赖 ECS + API Keys，默认 @Disabled，见 GoldenDatasetEvaluationIT）
```

多参数组合用逗号分隔：`-Dspring-boot.run.arguments=--eval.sample-size=10,--eval.annotate-query=问题`

### 4.3 报告解读

**报告产物双通道**：① 控制台 stdout 直出（不依赖日志配置，CI 日志必可见）；② 落盘 `target/eval-report.txt`（本地可复查）。

**门禁拒绝静默通过**（三种情况直接失败退出，而非"0 样本 → 跳过阈值 → 通过"）：
- `DASHSCOPE_API_KEY` 未配置（Judge 不可用）
- Golden Dataset 为空
- 全部用例执行失败（基础设施不可达/密钥错误）

初始状态（仅负向用例、语料未标注）的输出形如：

```
══════════ 评估报告 ══════════
检索探针:        vector-single
用例总数:        15（检索可评 0 / 生成可评 0 / 负向 15）
── 检索侧 ──
Top-K Recall:        无样本，跳过
MRR:                 无样本，跳过
Context Precision:   无样本，跳过
── 生成侧 ──
Faithfulness:        无样本，跳过
Response Relevancy:  无样本，跳过
── 鲁棒性 ──
Negative Rejection:  0.8667
══════════════════════════════
```

语料标注完成后（示例）：

```
用例总数:        53（检索可评 38 / 生成可评 38 / 负向 15）
── 检索侧 ──
Top-K Recall:        0.712      ← Phase 1 单路基线，2.5-2.9 调优后应 > 0.85
MRR:                 0.644
Context Precision:   0.583
── 生成侧 ──
Faithfulness:        4.21
Response Relevancy:  4.35
── 鲁棒性 ──
Negative Rejection:  0.8667
```

**基线价值**：Phase 1 单路检索的这组数字就是后续混合检索调优的对照基线——2.7+ 混合检索探针接入后同数据集复跑，差值即方案甲+ 的实际收益。

### 4.4 门禁规则（阈值表，对应设计文档 16.4）

| 指标 | Phase 2 阈值（建基线期，从宽） | Phase 5（校准后收紧） |
|---|---|---|
| Top-K Recall | ≥ 0.85 | 收紧 |
| MRR | ≥ 0.70 | 收紧 |
| Faithfulness | ≥ 4.0 | ≥ 4.5 |
| Negative Rejection | ≥ 0.85 | 收紧 |
| 较基线回归幅度 | > 3% 阻断（机制预留） | 启用 |

门禁只作用于**有样本**的指标；阈值配置在 `application.yml` 的 `eval.thresholds`，可经环境变量覆盖。

---

## 5. 配置参考

`src/main/resources/application.yml`（`eval.*` 前缀，`EvalProperties` 绑定）：

| 配置项 | 默认 | 说明 |
|---|---|---|
| `eval.top-k` | 5 | 检索探针 Top-K，与主链路 `Constants.DEFAULT_TOP_K` 一致 |
| `eval.sample-size` | 0 | 每类抽样上限，0=全量（CI 快跑设 10） |
| `eval.probe` | auto | 探针选择：auto / vector / hybrid（A/B 基线对比，`EVAL_PROBE` 覆盖） |
| `eval.ci.enabled` | false | ci profile 下 true，启用门禁 |
| `eval.judge.base-url` | 百炼 OpenAI 兼容端点 | `EVAL_JUDGE_BASE_URL` 覆盖 |
| `eval.judge.api-key` | `${DASHSCOPE_API_KEY}` | 复用百炼 Key |
| `eval.judge.model` | qwen3.7-plus | `EVAL_JUDGE_MODEL` 覆盖（更好效果可换 qwen3.7-max） |
| `eval.judge.temperature` | 0.0 | Judge 评分确定性 |
| `eval.thresholds.*` | 见 4.4 | 门禁阈值 |

基础设施连接配置经 `spring.config.import` 复用 `application-infra.yml` / `application-ai.yml`（kb-infrastructure / kb-ai-core jar 内），环境变量与 kb-api 完全同源。

---

## 6. 成本与注意事项

- **调用量**：每条正向用例 = 1 次 DeepSeek（被测）+ 2 次 qwen3.7-plus（Judge）；负向用例 = 1 + 1。50 条全量约 135 次模型调用，qwen3.7-plus 成本可忽略；CI 高频触发时用 `sample-size` 抽样。
- **Judge 隔离原则**：不要把 Judge 换成与被测相同的 DeepSeek（self-preference 偏差，16.3）；换模型后旧分数不可直接对比。
- **评估直连 Bean**：EvalRunner 调用的是 kb-ai-core 的 `chatClient`（含 Advisor 链的完整链路），与线上行为一致；不经过 HTTP 层，故不依赖 kb-api 与 JWT。
- **外部依赖故障**：单条用例 API 超时只记日志跳过；若大面积失败，报告样本数会锐减——先查基础设施再看分数。
- **expectedChunkIds 时效**：文档重新入库后 chunkId 变化，需重新标注受影响用例。

## 7. 演进路线

| 时点 | 内容 |
|---|---|
| Phase 2.7+ | `HybridRetrievalProbe`（order=0）自动替换单路探针，评估无感切换 |
| Phase 5 | Answer Correctness（启用 expectedAnswer）/ Citation Attribution / Noise Robustness 指标；Judge 人类校准（85-90% 一致率）；基线回归对比门禁；阈值收紧 |
| 持续 | 反馈闭环（16.6）：Bad Case → Golden Dataset 增补 → 回归测试 |
