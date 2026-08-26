# Phase 5 簇④ GraphRAG 详细实施方案（批次推进版）

> **版本**：v1.1 · **日期**：2026-08-26 · **工时**：10d · **模块跨度**：kb-infrastructure / kb-etl / kb-ai-core / kb-admin / kb-eval / kb-domain / frontend / kb-loadtest / docs
> **性质**：簇④落码执行基线（开工勘察三路结论 + 架构设计 + 用户四项定案 + 评审修正）。复审定案出处：`docs/project-optimization/Phase 5 复审与规划方案（调研实证版）.md` §五；批次进展回填 07 卷簇④段。
> **分支纪律**：全部提交落 `phase5-cluster4-graphrag`（2026-08-26 已建）；main 冻结至簇② 批5 用户侧回传（纪律延续簇③先例）。

## 定案记录（2026-08-26，用户拍板四项）

1. **Neo4j 客户端形态** = 原生驱动 + 自建 `GraphGateway`（手工装配 `Driver` Bean，不引 Spring Data Neo4j 全套）；
2. **Graph 路检索形态** = 实体向量匹配（抽取时实体描述嵌入存 Neo4j 向量索引；检索期查询嵌入 → 实体匹配 → 邻域展开 → chunk 反查，**检索期零 LLM 调用**，满足三路融合 P95 <600ms）；
3. **多跳测试集产出** = 机器侧草稿工具（`--eval.draft-multihop`，基于图实体链 + PG 真值起草）+ 用户审定，沿用簇② AC「机器侧草稿 + 人工审定」先例；
4. **存量建图** = kb-admin 图专项回填任务（直读 PG 存量 chunk，跳过解析/嵌入只走抽取→写图；复用重建滑动窗口 + Redis 任务表模式）。

**开工事实**：用户已在第二台 ECS 安装 Neo4j 社区最新版（簇④开工决策点解除）；版本 / bolt 地址 / 凭据经用户侧清单收集（§12.1）。

## 评审修正（2026-08-26，落码前审查）

- **R1（指标模块方向）**：抽取计数不可由 kb-ai-core `AiBusinessMetrics` 直接在 kb-etl 消费（模块不可见）。修正 = 依赖倒置先例（对齐 `ReindexGateway`：kb-admin 定义接口 / kb-api 实现）——kb-etl 定义 `GraphExtractionListener` SPI（started/succeeded/failed），kb-api 提供实现委派 `AiBusinessMetrics`，`GraphExtractionService` 经 `ObjectProvider` 容忍缺位；
- **R2（embedding 维度）**：1024 维已三处核验钉死（pgvector `KB_PGVECTOR_DIMENSIONS` / Milvus `embedding-dimension` / 语义缓存 `rag.cache.embedding-dim`，同源同值），向量索引 DDL 直接采用，落码时仍经 `EmbeddingModel` 首嵌实测复核；
- **R3（配置落位）**：`spring.neo4j.*` 连接段落 `application-infra.yml`（基建连接纪律，与 PG/ES/Redis/MinIO 同层）；`rag.graph.*` 功能段落 `application-ai.yml`；
- **R4（依赖版本）**：`neo4j-java-driver` 版本先核验 Boot 4.1 `spring-boot-dependencies` 是否托管（本地 4.1.0 BOM 在案），托管则免版本号，未托管则钉稳定版入父 POM `dependencyManagement`。

---

## 0. 总览与纪律

### 0.1 簇④边界

| 子项 | 交付物 | 落模块 |
|------|--------|--------|
| 5.1 Neo4j 网关 + 抽取管道 | GraphGateway + EntityExtractor + 回填任务 | kb-infrastructure + kb-etl + kb-admin |
| 5.2 三路融合检索 | N 路 RRF + GraphDocumentRetriever + 降级开关 | kb-ai-core |
| 多跳测试集 ≥30 例 | golden/multihop/*.json + 草稿工具 + 评估接线 | kb-eval |
| 前端/压测/文档 | Debug.vue 扩维 + LoadTestConfig 阈值 + 10/13/17/18 章 | frontend + kb-loadtest + docs |

### 0.2 不可破纪律

1. **`rag.graph.enabled` 缺省 `false`**——关闭态链路形态零变化（无条件装配，`ObjectProvider<GraphDocumentRetriever>.getIfAvailable()` 消费）
2. **租户隔离两层**：① 入口身份守卫（已有）；② 图查询注入 `tenant_id` 过滤，fail-closed（`RetrievalContext` 无 `tenantId` → 图路返回空列表）
3. **检索期零 LLM 调用**——Graph 路纯向量索引匹配 + Cypher 展开 + PG 反查
4. **产出物不含攻击字面**（红线）
5. **指标零租户标签纪律**沿用
6. **Flyway 双源守卫**：迁移 V2 + schema.sql 快照同步

---

## 1. 批次分解

### 批1：核心基建（3d）——图 Schema + Neo4j 网关 + 抽取管道核心

**目标**：图数据可写入、可查询、可溯源；抽取管道单 chunk 可跑通

| 序号 | 模块 | 文件/类 | 说明 |
|------|------|---------|------|
| 1.1 | kb-domain | `db/migration/V2__graph_status.sql` | `kb_document` 增 `graph_status` 列（`VARCHAR(20) DEFAULT 'PENDING'`）+ `graph_updated_at` 列 |
| 1.2 | kb-domain | `schema.sql` | 同步 V2 DDL 快照 |
| 1.3 | kb-domain | `KbDocument.java` | 增 `graphStatus` / `graphUpdatedAt` 字段 |
| 1.4 | kb-domain | `enums/GraphStatus.java` | 枚举：`PENDING / EXTRACTING / COMPLETED / FAILED / SKIPPED` |
| 1.5 | kb-infrastructure | `pom.xml` | 新增 `org.neo4j.driver:neo4j-java-driver`（版本形态见评审修正 R4：先核验 Boot 4.1 BOM 托管，未托管则父 POM `dependencyManagement` 钉版本） |
| 1.6 | kb-infrastructure | `graph/Neo4jConfig.java` | `@ConditionalOnProperty(prefix="rag.graph", name="enabled", havingValue="true")` + `Driver` Bean 手工装配（不引 Spring Data Neo4j） |
| 1.7 | kb-infrastructure | `graph/Neo4jProperties.java` | `@ConfigurationProperties(prefix="spring.neo4j")` — uri/user/password/connectionTimeout |
| 1.8 | kb-infrastructure | `graph/GraphGateway.java` | 图读写网关接口 + Neo4j 实现：`writeEntities(tenantId, docId, chunkIds, entities, relations)` / `deleteByDocId(tenantId, docId)` / `findChunksByEntityVector(embedding, tenantId, topN, threshold)` / `ensureSchema()` |
| 1.9 | kb-infrastructure | `graph/GraphSchemaInitializer.java` | 启动时幂等执行 Cypher DDL（约束 + 向量索引），`@EventListener(ApplicationReadyEvent)` 或 `SmartInitializingSingleton` |
| 1.10 | kb-etl | `pipeline/EntityExtractor.java` | 结构化抽取核心：`ChatModel` 直连 qwen3.7-plus（复用 `fallbackChatModel` Bean 或独立装配）+ `.entity(ExtractionResult.class)` 结构化输出 |
| 1.11 | kb-etl | `pipeline/ExtractionResult.java` | 抽取输出 record（`entities` / `relations`），§3 契约详述 |
| 1.12 | kb-etl | `pipeline/GraphExtractionService.java` | 抽取编排：限流 + 实体嵌入 + 幂等写图 + 状态回写 |
| 1.13 | kb-etl | `pom.xml` | 依赖 kb-infrastructure（GraphGateway） |

**单测增量面**：
- `kb-domain`: `V2MigrationSchemaTest`（双源守卫单测扩 V2 列）
- `kb-infrastructure`: `Neo4jConfigTest`（条件装配开关验证）+ `GraphSchemaInitializerTest`（Cypher 幂等性 mock 验证）
- `kb-etl`: `EntityExtractorTest`（结构化输出 JSON 解析 mock）+ `GraphExtractionServiceTest`（幂等重写语义 + 限流 mock + 异常容错）

**验证命令**：
```bash
mvn -q --no-transfer-progress test -pl kb-domain,kb-infrastructure,kb-etl -am
```

**设计回写**：10 章 §10.9（GraphRAG 三路融合）初稿 + 9 章 §9.4（图 Schema）

**提交**：`feat(graph): 批1 图 Schema + Neo4j 网关 + 抽取管道核心`

---

### 批2：接线集成（3d）——ETL 触发 + 图检索 + 三路融合

**目标**：ETL COMPLETED 帧触发抽取；图检索可独立运行；三路 RRF 融合可切换

| 序号 | 模块 | 文件/类 | 说明 |
|------|------|---------|------|
| 2.1 | kb-ai-core | `retriever/GraphDocumentRetriever.java` | 图路检索器：查询嵌入 → 向量索引实体匹配 → 邻域展开 → chunk 反查 → 输出 `List<Document>`（metadata 携 `graph_rank` / 实体命中信息） |
| 2.2 | kb-ai-core | `retriever/RrfFusion.java` | **泛化为 N 路**：`fuse(Map<String, List<Document>> routeHits, int limit)` 新签名 + 旧双路签名委派（兼容策略，见 §5） |
| 2.3 | kb-ai-core | `retriever/HybridDocumentRetriever.java` | 三路并行接线：`ObjectProvider<GraphDocumentRetriever>` 条件消费；graph 路 `executor.submit` 并行 + trace `"graph"` 条目 |
| 2.4 | kb-ai-core | `config/RetrievalProperties.java` | 增 `graph` 嵌套配置组（`topN` / `hops` / `decay` / `entitySimilarityThreshold` / `pathTimeoutSeconds`） |
| 2.5 | kb-ai-core | `config/RetrievalConfig.java` | `GraphDocumentRetriever` 条件 Bean（`@ConditionalOnProperty(prefix="rag.graph", name="enabled", havingValue="true")`） |
| 2.6 | kb-ai-core | `metrics/AiBusinessMetrics.java` | 新增 `rag.retrieval.graph.total / hit / latency` + `rag.graph.extraction.total / succeeded / failed` 计数器 |
| 2.7 | kb-etl | `service/DocumentEtlService.java` | **不直接挂接**——抽取触发经 `DocumentService.reindexProgressCallback()` COMPLETED 帧异步派发（见 2.8） |
| 2.8 | kb-api | `service/DocumentService.java` | COMPLETED 帧增 `graphExtractionPublisher.ifAvailable(pub -> pub.publish(tenantId, docId))` ——同位挂接缓存失效旁 |
| 2.9 | kb-etl | `pipeline/GraphExtractionPublisher.java` | 事件发布接口（kb-etl 定义，kb-api 接线）：`publish(tenantId, docId)` 异步触发 `GraphExtractionService` |
| 2.10 | kb-ai-core | `retriever/RetrievalContext.java` | trace entry 已支持任意 source 字符串，graph 路 `addTraceEntry("graph", hits, latencyMs)` 零改动 |
| 2.11 | kb-ai-core | `application-ai.yml` | 增 `rag.graph.*` 配置族（§7 全清单） |

**单测增量面**：
- `kb-ai-core`: `GraphDocumentRetrieverTest`（mock GraphGateway + 超时降级 + 租户 fail-closed）+ `RrfFusionNWayTest`（N 路融合 + 双路兼容回归）+ `HybridDocumentRetrieverThreeWayTest`（三路并行 + graph 关态零回归）
- `kb-etl`: `GraphExtractionPublisherTest`（事件触发 mock）

**验证命令**：
```bash
mvn -q --no-transfer-progress test -pl kb-ai-core,kb-etl -am
```

**设计回写**：10 章 §10.9 完善 + §10.4 RRF N 路泛化 + 11 章链序表更新

**提交**：`feat(graph): 批2 ETL 触发接线 + 图检索 + 三路 RRF 融合`

---

### 批3：生命周期 + 回填任务 + 失效清理（2d）

**目标**：文档删除/重建时图数据清理；存量回填可运行

| 序号 | 模块 | 文件/类 | 说明 |
|------|------|---------|------|
| 3.1 | kb-api | `service/DocumentService.java` | `delete()` 增图清理：`graphGateway.deleteByDocId(tenantId, docId)`（尽力而为，不阻断删除） |
| 3.2 | kb-admin | `service/ChunkOpsService.java` | chunk 软删/恢复增图引用标记更新（非删除——实体保留，chunk 引用列表更新） |
| 3.3 | kb-admin | `service/GraphBackfillService.java` | 图专项回填任务：直读 PG 存量 chunk（跳过解析/嵌入）→ 抽取 → 写图；复用 `IndexRebuildService` 滑动窗口 + `RedisRebuildTaskStore` 模式 |
| 3.4 | kb-admin | `service/RedisGraphTaskStore.java` | 回填任务 Redis 存储（同 `RedisRebuildTaskStore` 形态，独立键前缀 `rag:graph-backfill:*`） |
| 3.5 | kb-admin | `controller/AdminController.java` | 回填端点 `POST /api/v1/admin/graph/backfill`（全量/目标文档，同重建端点形态） |
| 3.6 | kb-admin | `dto/GraphBackfillRequest.java` | 请求体（docIds 可选） |
| 3.7 | kb-admin | `dto/GraphBackfillTaskView.java` | 任务视图 |
| 3.8 | kb-etl | `pipeline/GraphExtractionService.java` | 幂等重写：同文档重复抽取先 `graphGateway.deleteByDocId` 再写入（§6 详述） |
| 3.9 | kb-infrastructure | `scripts/neo4j-backup.sh` | Neo4j 备份脚本（`neo4j-admin database dump`，同 `pg-backup.sh` 形态） |

**单测增量面**：
- `kb-admin`: `GraphBackfillServiceTest`（滑动窗口 + Redis 任务表 mock + 幂等重写）+ `AdminControllerGraphTest`（端点守卫）
- `kb-api`: `DocumentServiceGraphCleanupTest`（删除路径图清理 mock）

**验证命令**：
```bash
mvn -q --no-transfer-progress test -pl kb-admin,kb-api -am
```

**设计回写**：9 章 §9.4（图生命周期）+ 17 章 §17.5（灾备扩展）

**提交**：`feat(graph): 批3 生命周期清理 + 存量回填任务 + 备份脚本`

---

### 批4：多跳测试集 + 评估接线 + 前端/压测（1.5d）

**目标**：多跳集 ≥30 例可运行 + 评估报告含多跳准确率 + 前端 Debug 扩维 + 压测阈值更新

| 序号 | 模块 | 文件/类 | 说明 |
|------|------|---------|------|
| 4.1 | kb-eval | `dataset/QACategory.java` | 增 `MULTI_HOP` 枚举值（见 §10 取舍论证） |
| 4.2 | kb-eval | `dataset/GoldenQAPair.java` | 增 `expectedEntityChain` 字段（多跳路径实体链标注，可空） |
| 4.3 | kb-eval | `golden/multihop/` | 新增多跳专项目录（≥30 例），glob 扫描自动收纳 |
| 4.4 | kb-eval | `runner/MultiHopDraftRunner.java` | 机器侧草稿工具：`--eval.draft-multihop`（基于图实体链 + PG 真值起草，沿用 AnswerDraftRunner 先例） |
| 4.5 | kb-eval | `runner/EvalRunner.java` | 多跳准确率计算：`MULTI_HOP` 分类走 Judge 管道（`expectedAnswer` + `expectedEntityChain` 双维度评分） |
| 4.6 | kb-eval | `metric/MultiHopMetrics.java` | 多跳专项指标：实体链覆盖率 + 跳数完整性 + 准确率（>80% 门禁） |
| 4.7 | frontend | `Debug.vue` | `scoreDims()` 扩 graph 维度 + latencyMs 扩 graph 段 |
| 4.8 | frontend | `Chat.vue` | TRACE `Source.source` 新增 "graph" → default 分支 + label/chip 样式 |
| 4.9 | kb-loadtest | `LoadTestConfig.java` | `aP95ThresholdMs` 上调 500→600（三路融合后延迟预算放宽） |
| 4.10 | kb-loadtest | 场景 A 描述 | §18.4 同步更新 |

**单测增量面**：
- `kb-eval`: `MultiHopDraftRunnerTest` + `MultiHopMetricsTest` + `GoldenDatasetLoaderTest`（扩 MULTI_HOP 解析）
- `frontend`: 无单测（E2E 验证）

**验证命令**：
```bash
mvn -q --no-transfer-progress test -pl kb-eval -am
```

**设计回写**：16 章 §16.1（MULTI_HOP 分类）+ 18 章 §18.4（阈值）

**提交**：`feat(graph): 批4 多跳测试集 + 评估接线 + 前端/压测扩维`

---

### 批5：E2E 交付 + 文档收口 + 提交（0.5d）

**目标**：全模块单测绿 + E2E 步骤文档 + 用户侧清单 + 文档三件套

| 序号 | 内容 |
|------|------|
| 5.1 | 全模块单测：`mvn -q --no-transfer-progress test` 全绿 |
| 5.2 | kb-eval IT（可选，Docker 必需）：`mvn verify -pl kb-eval -am -DskipITs`（无 Docker 跳过 IT） |
| 5.3 | E2E 测试步骤文档（`docs/delivery/` 落三件套） |
| 5.4 | 用户侧待执行项清单更新（Neo4j 连接信息 / 备份脚本 / 回填任务操作） |
| 5.5 | CLAUDE.md 同步：GraphRAG 架构事实入「当前实现要点」 |
| 5.6 | 10 章 §10.4/§10.9 + 13 章 §13.3 指标登记表 + 17 章 §17.5 灾备 + 18 章 §18.4 阈值 |
| 5.7 | `infra/.env.example` 增 `NEO4J_*` 键族 |
| 5.8 | 提交：`feat(graph): 批5 全模块验证 + E2E 交付 + 文档收口` |

---

## 2. 图 Schema 设计

### 2.1 节点类型

#### `Entity`（实体节点）

| 属性 | 类型 | 说明 |
|------|------|------|
| `id` | STRING (PK) | 确定性 ID：`nameUUID v3(name + type + tenantId)` |
| `name` | STRING | 实体名称（规范化小写 + trim） |
| `type` | STRING | 实体类型（PERSON / ORG / PRODUCT / CONCEPT / LOCATION / TECH / EVENT / OTHER） |
| `description` | STRING | 实体描述（抽取时 LLM 生成，≤200 字） |
| `embedding` | FLOAT[1024] | 描述向量（qwen3.7-text-embedding 同源，维度 1024 钉死） |
| `tenant_id` | STRING | 租户 ID（隔离必携） |
| `doc_ids` | LIST<STRING> | 出现文档 ID 集合（溯源） |
| `chunk_ids` | LIST<STRING> | 出现 chunk ID 集合（反查） |
| `mention_count` | INTEGER | 出现次数（合并计数） |
| `created_at` | STRING | ISO 时间戳 |
| `updated_at` | STRING | ISO 时间戳 |

#### `Chunk`（chunk 引用节点——轻量锚点，非完整内容）

| 属性 | 类型 | 说明 |
|------|------|------|
| `id` | STRING (PK) | = `kb_chunk.id`（确定性 ID，与 PG 同源） |
| `doc_id` | STRING | 所属文档 |
| `tenant_id` | STRING | 租户 ID |
| `chunk_index` | INTEGER | 序号 |
| `is_deleted` | BOOLEAN | 软删标记（同步自 PG） |

> **设计理由**：Chunk 节点不在 Neo4j 存内容（PG 事实源），仅作实体→chunk 关联锚点。检索路径：实体匹配 → 邻域展开 → `Chunk` 节点 → `chunk_id` 列表 → PG 反查内容。

### 2.2 关系类型

| 关系 | 方向 | 属性 | 说明 |
|------|------|------|------|
| `RELATED_TO` | Entity → Entity | `weight` (FLOAT), `relation_type` (STRING), `doc_ids` (LIST), `chunk_ids` (LIST) | 实体间语义关系 |
| `MENTIONS` | Chunk → Entity | `position` (INTEGER) | chunk 提及实体（反向 = 实体被哪些 chunk 引用） |

> **关系去重**：`RELATED_TO` 以 `(source_id, target_id, relation_type)` 为幂等键，MERGE 语义。

### 2.3 约束与索引（幂等 Cypher）

```cypher
// 约束（幂等：CREATE CONSTRAINT IF NOT EXISTS）
CREATE CONSTRAINT entity_id IF NOT EXISTS FOR (e:Entity) REQUIRE e.id IS UNIQUE;
CREATE CONSTRAINT chunk_id IF NOT EXISTS FOR (c:Chunk) REQUIRE c.id IS UNIQUE;

// 复合索引（租户隔离查询加速）
CREATE INDEX entity_tenant_name IF NOT EXISTS FOR (e:Entity) ON (e.tenant_id, e.name);
CREATE INDEX entity_tenant_type IF NOT EXISTS FOR (e:Entity) ON (e.tenant_id, e.type);
CREATE INDEX chunk_tenant_doc IF NOT EXISTS FOR (c:Chunk) ON (c.tenant_id, c.doc_id);
CREATE INDEX chunk_doc_deleted IF NOT EXISTS FOR (c:Chunk) ON (c.doc_id, c.is_deleted);

// 向量索引（Neo4j 5.x 内建向量索引，1024 维余弦相似度）
// 落码前核验：Neo4j Community 5.x CREATE VECTOR INDEX 语法与参数
CREATE VECTOR INDEX entity_embedding IF NOT EXISTS
  FOR (e:Entity) ON (e.embedding)
  OPTIONS {indexConfig: {
    `vector.dimensions`: 1024,
    `vector.similarity_function`: 'cosine'
  }};
```

### 2.4 ID/去重策略

**实体 ID**：`UUID.nameUUIDFromBytes((name + "|" + type + "|" + tenantId).getBytes(UTF_8))`

**合并语义**：同租户 + 同名 + 同类型 → 同 ID → MERGE 覆写：
- `description` 取最新（或最长）
- `embedding` 取最新描述重嵌入
- `doc_ids` / `chunk_ids` 取并集
- `mention_count` 累加

**跨租户隔离**：实体 ID 含 `tenantId`，同名称不同租户 → 不同节点，物理隔离。

### 2.5 租户隔离查询形态

```cypher
// 向量匹配（参数化，fail-closed：$tenantId 必填）
CALL db.index.vector.queryNodes('entity_embedding', $topN, $queryVector)
YIELD node AS e, score
WHERE e.tenant_id = $tenantId AND score >= $threshold
RETURN e, score ORDER BY score DESC

// 邻域展开（参数化）
MATCH (e:Entity)-[r:RELATED_TO]-(neighbor:Entity)
WHERE e.id IN $seedIds AND neighbor.tenant_id = $tenantId
RETURN neighbor, r, score * $decay AS expanded_score

// chunk 反查（参数化 + 软删过滤）
MATCH (c:Chunk)-[:MENTIONS]->(e:Entity)
WHERE e.id IN $entityIds AND c.tenant_id = $tenantId AND c.is_deleted = false
RETURN DISTINCT c.id AS chunk_id, c.doc_id
```

---

## 3. 抽取管道契约

### 3.1 结构化输出 record

```java
/**
 * 抽取输出（.entity() 映射目标，qwen3.7-plus 结构化输出）
 * Jackson 3 命名空间（坑位⑬）：tools.jackson.annotation.JsonProperty
 */
public record ExtractionResult(
    List<EntityExtraction> entities,
    List<RelationExtraction> relations
) {}

public record EntityExtraction(
    String name,           // 实体名称（规范化）
    String type,           // PERSON / ORG / PRODUCT / CONCEPT / LOCATION / TECH / EVENT / OTHER
    String description     // ≤200 字描述
) {}

public record RelationExtraction(
    String sourceName,     // 源实体名称
    String targetType,     // 目标实体名称
    String relationType,   // 关系类型（如 WORKS_AT / PART_OF / RELATED_TO）
    String description     // 关系描述（≤100 字）
) {}
```

### 3.2 抽取粒度

**按 chunk 为单位抽取**，附窗口上下文：

- 输入 prompt 包含：当前 chunk 全文 + 前后各 1 chunk 文本（窗口上下文，缓解边界切断）
- 单次 LLM 调用输入 ≤2000 token（控制成本）
- chunk 过长（>1500 字）截断并标记 `TRUNCATED`

### 3.3 限流形态

**双限流**：

1. **令牌桶**（Redisson `RRateLimiter`）：`rag:ratelimit:graph-extraction:{tenantId}`
   - 速率建议：`10 permits / 60 seconds`（单租户每分钟 10 次抽取调用）
   - 理由：qwen3.7-plus 百炼端点 RPM 限制 + 避免业务高峰争资源
   - Redis 故障 fail-open（抽取是非关键路径，限流是成本管控非安全边界）

2. **JVM 信号量**（`Semaphore`）：`graphExtractionGate`
   - 并发数：`rag.graph.extraction.concurrency`（默认 3）
   - 理由：控制内存中同时进行的抽取任务数，避免 LLM 调用密集

### 3.4 幂等重写语义

同文档重复抽取（reparse / replace / 重建触发）：

1. 先 `graphGateway.deleteByDocId(tenantId, docId)` —— 删除该文档所有 `Chunk` 节点及关联 `MENTIONS` 关系
2. 实体节点**不直接删除**——从 `doc_ids` / `chunk_ids` 中移除当前 docId/chunkIds
3. 若实体 `doc_ids` 变空 → 删除该实体节点（孤儿清扫）
4. 重新写入新抽取结果（MERGE 语义）

### 3.5 失败处理

- **抽取失败**：不阻断 ETL 主流程；`kb_document.graph_status = FAILED`；`rag.graph.extraction.failed` 计数
- **写图失败**：同上；记录 `error_message` 到 document 或独立日志
- **重试**：回填任务可手动重发（幂等语义保证收敛）；不做自动重试（避免 LLM 计费重复）

### 3.6 触发接线

**双入口**：

1. **ETL COMPLETED 帧**（`DocumentService.reindexProgressCallback()` L286-300 同位挂接）：
   ```java
   graphExtractionPublisher.ifAvailable(pub -> pub.publish(tenantId, docId));
   ```
   覆盖面：首次入库 / reparse / replace / 重建（重建委派 reparse 同路径）

2. **回填任务**（kb-admin `GraphBackfillService`）：直读 PG 存量 chunk，跳过解析/嵌入，只抽取→写图

### 3.7 模型选型

**推荐：复用 `fallbackChatModel` Bean（qwen3.7-plus 百炼直连）**

理由：
- 已有 `@ConditionalOnProperty("rag.routing.fallback.enabled", matchIfMissing=true)` 条件装配
- qwen3.7-plus 支持 `.entity(Class)` 结构化输出（四处先例）
- 低温度（`temperature: 0.0`）+ `enable_thinking: false`（坑位⑮）
- 与主模型 DeepSeek V4 跨厂商隔离（抽取不影响对话链）
- 成本控制：qwen3.7-plus 百炼端点价格远低于 DeepSeek V4

**备选**：独立装配 `graphExtractionChatModel` Bean（同 baseUrl/apiKey，独立 options）——`fallbackChatModel` 关闭时仍可用

---

## 4. 图检索算法

### 4.1 算法流程

```
查询文本 → qwen3.7-text-embedding 嵌入（复用现有 EmbeddingModel Bean）
         → Neo4j 向量索引实体匹配（topN=5, threshold=0.7）
         → 邻域展开（1 跳，双向，衰减 0.5）
         → 候选实体集合（种子 + 展开，去重）
         → Chunk 反查（MENTIONS 关系 → chunk_id 列表）
         → PG 反查内容（KbChunkRepository.findAllById + 租户/软删过滤）
         → 输出 List<Document>（metadata 携 graph_rank / entity_hits / hop）
```

### 4.2 延迟预算分解（总 ~100ms 量级）

| 步骤 | 延迟 | 说明 |
|------|------|------|
| 查询嵌入 | ~30ms | 复用现有 EmbeddingModel（qwen3.7-text-embedding） |
| 向量索引匹配 | ~10ms | Neo4j 内建向量索引，1024 维 KNN |
| 邻域展开 | ~10ms | Cypher 1 跳展开，索引加速 |
| Chunk 反查 | ~5ms | Cypher 关系遍历 |
| PG 反查 | ~20ms | `findAllById` + JPA |
| 网络开销 | ~15ms | bolt 协议 + PG 查询 |
| **合计** | **~90ms** | 单路预算 100ms，留 10ms 余量 |

### 4.3 降级语义

- **超时**（`rag.graph.path-timeout-seconds` 默认 5，与双路同口径）：`Future.get` 超时 → cancel + 空列表
- **Neo4j 连接异常**：`retrieveSafely` 捕获 → 空列表 + warn 日志
- **嵌入失败**：同上
- **PG 反查失败**：同上

### 4.4 输出 Document metadata

```java
metadata.put("graph_rank", rank);          // 图路排名（1-based）
metadata.put("entity_hits", entityNames);  // 命中实体名列表（溯源）
metadata.put("hop", hopLevel);             // 0=种子 / 1=展开
metadata.put("entity_score", score);       // 实体匹配分数
```

---

## 5. 三路融合改造

### 5.1 RrfFusion 泛化签名

```java
/**
 * N 路 RRF 融合（泛化形态）
 *
 * @param routeHits 路标识 → 该路命中列表（按分数降序）；
 *                  路标识 = "vector" / "bm25" / "graph" / ...
 * @param limit     融合结果上限
 */
public List<Document> fuse(Map<String, List<Document>> routeHits, int limit) {
    int rrfK = properties.getRrfK();
    Map<String, FusedEntry> table = new LinkedHashMap<>();

    for (Map.Entry<String, List<Document>> route : routeHits.entrySet()) {
        String routeName = route.getKey();
        List<Document> hits = route.getValue();
        for (int i = 0; i < hits.size(); i++) {
            Document d = hits.get(i);
            FusedEntry entry = table.computeIfAbsent(d.getId(), k -> new FusedEntry(d));
            entry.mergeFrom(d);
            entry.setRank(routeName, i + 1);
        }
    }
    // ... 后续同现有：computeFusionScore + demote + sort + limit
}

/**
 * 兼容签名（旧双路调用方零改动）
 */
public List<Document> fuse(List<Document> vectorHits, List<Document> bm25Hits, int limit) {
    Map<String, List<Document>> routeHits = new LinkedHashMap<>();
    routeHits.put("vector", vectorHits);
    routeHits.put("bm25", bm25Hits);
    return fuse(routeHits, limit);
}
```

**FusedEntry 改造**：
- `Map<String, Integer> routeRanks` 替代 `vectorRank` / `bm25Rank` 双字段
- `computeFusionScore`：遍历 `routeRanks` 累加 `1.0 / (rrfK + rank)`
- `toDocument`：metadata 写入 `{route}_rank` 键（vector_rank / bm25_rank / graph_rank）

### 5.2 HybridDocumentRetriever 三路并行

```java
// 构造注入
private final ObjectProvider<GraphDocumentRetriever> graphRetrieverProvider;

// retrieve() 内
Future<List<Document>> graphFuture = graphRetrieverProvider.ifAvailable(gr ->
    executor.submit(() -> retrieveSafely(() -> gr.retrieve(query), "graph"))
);
// graph 路为 null 时 future 为空，后续 await 跳过

List<Document> graphHits = (graphFuture != null) ? await(graphFuture, "graph") : List.of();

// trace
if (ctx != null && !graphHits.isEmpty()) {
    ctx.addTraceEntry("graph", graphHits, graphLatency);
}

// N 路融合
Map<String, List<Document>> routeHits = new LinkedHashMap<>();
routeHits.put("vector", vectorHits);
routeHits.put("bm25", bm25Hits);
if (!graphHits.isEmpty()) {
    routeHits.put("graph", graphHits);
}
List<Document> fused = rrfFusion.fuse(routeHits, recallSize);
```

### 5.3 条件装配方案

```java
// RetrievalConfig.java
@Bean
@ConditionalOnProperty(prefix = "rag.graph", name = "enabled", havingValue = "true")
public GraphDocumentRetriever graphDocumentRetriever(
        GraphGateway graphGateway,
        EmbeddingModel embeddingModel,
        KbChunkRepository chunkRepository,
        RetrievalProperties properties) {
    return new GraphDocumentRetriever(graphGateway, embeddingModel, chunkRepository, properties);
}
```

消费侧 `ObjectProvider<GraphDocumentRetriever>.getIfAvailable()` —— `rag.graph.enabled=false` 时 Bean 不存在，`ifAvailable` 返回 null，graph 路零触达，**链路形态与开关前逐字节一致**。

---

## 6. 生命周期与失效

### 6.1 文档删除

`DocumentService.delete()` 增：
```java
// 尽力而为清理图数据，不阻断删除
try {
    graphGateway.ifAvailable(gw -> gw.deleteByDocId(tenantId, docId));
} catch (Exception e) {
    log.warn("图数据清理失败（不阻断删除）: docId={}", docId, e);
}
```

`deleteByDocId` 语义：
1. 删除该 docId 所有 `Chunk` 节点及 `MENTIONS` 关系
2. 遍历关联 `Entity` 节点，从 `doc_ids` / `chunk_ids` 移除该 docId 及 chunkIds
3. `doc_ids` 变空的 `Entity` → 删除（孤儿清扫）
4. `RELATED_TO` 关系的 `doc_ids` 同步清理，`doc_ids` 变空 → 删除关系

### 6.2 文档重建（reparse / replace）

ETL COMPLETED 帧触发重新抽取 → 幂等重写（§3.4）

### 6.3 Chunk 软删/恢复

- **软删**（`ChunkOpsService.softDelete`）：Neo4j `Chunk` 节点 `is_deleted = true`；实体节点不删（引用保留）
- **恢复**（`ChunkOpsService.restore`）：`is_deleted = false`；图检索过滤 `is_deleted = false` 生效

### 6.4 与语义缓存失效的关系

- 缓存失效（`cacheInvalidationPublisher.publish`）与图抽取触发（`graphExtractionPublisher.publish`）**独立并行**
- 两者均在 ETL COMPLETED 帧触发，但职责正交：缓存失效清旧缓存，图抽取更新图数据
- 不做串行依赖（图抽取耗时长，不应阻塞缓存失效广播）

---

## 7. 配置族全清单

### 7.1 `rag.graph.*`（application-ai.yml）

```yaml
rag:
  graph:
    # 总开关（缺省关——关闭态链路形态零变化）
    enabled: ${RAG_GRAPH_ENABLED:false}

    # 抽取配置
    extraction:
      rate: ${RAG_GRAPH_EXTRACTION_RATE:10}           # 令牌桶速率（次/分钟/租户）
      rate-interval-seconds: ${RAG_GRAPH_EXTRACTION_RATE_INTERVAL:60}
      concurrency: ${RAG_GRAPH_EXTRACTION_CONCURRENCY:3}  # JVM 信号量并发数
      window-chars: ${RAG_GRAPH_EXTRACTION_WINDOW_CHARS:1500}  # 上下文窗口字符数
      max-chunk-chars: ${RAG_GRAPH_EXTRACTION_MAX_CHUNK_CHARS:1500}  # 单 chunk 截断阈值

    # 检索配置
    retrieval:
      entity-top-n: ${RAG_GRAPH_ENTITY_TOP_N:5}              # 向量索引实体匹配 topN
      entity-similarity-threshold: ${RAG_GRAPH_ENTITY_SIMILARITY_THRESHOLD:0.7}
      hops: ${RAG_GRAPH_HOPS:1}                               # 邻域展开跳数
      decay: ${RAG_GRAPH_DECAY:0.5}                            # 展开衰减系数
      path-timeout-seconds: ${RAG_GRAPH_PATH_TIMEOUT_SECONDS:5}  # 单路超时（与双路同口径）
```

### 7.2 `spring.neo4j.*`（kb-infrastructure Neo4jProperties）

```yaml
spring:
  neo4j:
    uri: ${NEO4J_URI:bolt://host.docker.internal:7687}
    authentication:
      username: ${NEO4J_USERNAME:neo4j}
      password: ${NEO4J_PASSWORD:}
    connection-timeout-seconds: ${NEO4J_CONNECTION_TIMEOUT:10}
```

### 7.3 `NEO4J_*` env 键（infra/.env.example）

```bash
# ── Neo4j（第二台 ECS，簇④ GraphRAG）────────────
NEO4J_URI=bolt://NEO4J_HOST:7687
NEO4J_USERNAME=neo4j
NEO4J_PASSWORD=CHANGE_ME
NEO4J_CONNECTION_TIMEOUT=10
# 备份（neo4j-backup.sh，宿主侧消费）
BACKUP_NEO4J_HOST=127.0.0.1
BACKUP_NEO4J_PORT=7687
```

---

## 8. Flyway V2 设计

### 8.1 迁移脚本 `V2__graph_status.sql`

```sql
-- V2: 图抽取状态追踪（Phase 5 簇④ GraphRAG）
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS graph_status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS graph_updated_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_doc_graph_status ON kb_document (graph_status);
```

### 8.2 KbDocument 实体

```java
@Enumerated(EnumType.STRING)
@Column(name = "graph_status", length = 20)
private GraphStatus graphStatus = GraphStatus.PENDING;

@Column(name = "graph_updated_at")
private LocalDateTime graphUpdatedAt;
```

### 8.3 GraphStatus 枚举

```java
public enum GraphStatus {
    PENDING,      // 待抽取（新入库 / 图功能首次启用）
    EXTRACTING,   // 抽取中
    COMPLETED,    // 抽取完成
    FAILED,       // 抽取失败（可重试）
    SKIPPED       // 跳过（图功能关闭 / 文档类型不支持）
}
```

### 8.4 schema.sql 同步

在 `schema.sql` 的 `kb_document` 表定义中增：
```sql
graph_status     VARCHAR(20) NOT NULL DEFAULT 'PENDING',
graph_updated_at TIMESTAMP,
```

---

## 9. 指标/观测/前端/压测接线清单

### 9.1 新增指标（AiBusinessMetrics 构造器注册）

| 指标名 | 类型 | 接线点 | 说明 |
|--------|------|--------|------|
| `rag.retrieval.graph.total` | Counter | GraphDocumentRetriever | 图路检索执行次数 |
| `rag.retrieval.graph.hit` | Counter | GraphDocumentRetriever | 图路命中次数（结果非空） |
| `rag.retrieval.graph.latency` | Timer | GraphDocumentRetriever | 图路检索耗时（p50/p95/p99） |
| `rag.graph.extraction.total` | Counter | GraphExtractionService（经 SPI，见下注） | 抽取执行次数 |
| `rag.graph.extraction.succeeded` | Counter | 同上 | 抽取成功次数 |
| `rag.graph.extraction.failed` | Counter | 同上 | 抽取失败次数 |

全部零租户标签纪律沿用。

> **抽取族指标模块方向（评审修正 R1）**：检索族三项由 `AiBusinessMetrics`（kb-ai-core）直接注册消费；抽取族三项因 `GraphExtractionService` 在 kb-etl（kb-ai-core 不可见），走依赖倒置——kb-etl 定义 `GraphExtractionListener` SPI（`started / succeeded / failed`），kb-api 提供实现委派 `AiBusinessMetrics`（对齐 `ReindexGateway` kb-admin 定义 / kb-api 实现先例），`GraphExtractionService` 经 `ObjectProvider<GraphExtractionListener>` 容忍缺位（无实现时静默跳过，不阻断抽取）。

### 9.2 观测 span

- `GraphDocumentRetriever.retrieve` → 观测名 `graph.retrieval`（`Observation.createNotStarted`）
- `GraphExtractionService.extract` → 观测名 `graph.extraction`
- Neo4j 驱动层 span 由 `neo4j-java-driver` 内建（5.x 支持 OpenTelemetry）

### 9.3 Debug.vue 改动

`scoreDims()` 扩：
```javascript
scoreDims() {
  return ['vector_rank', 'bm25_rank', 'graph_rank', 'fusion_score'];
}
```

`latencyMs` 分段扩：
```javascript
// 现：vector / bm25 / rerank / total
// 扩：vector / bm25 / graph / rerank / total
```

### 9.4 Chat.vue 改动

TRACE `Source.source` 开放字符串已有 default 分支，新增 `"graph"` 走 default：
```javascript
// source chip 样式映射
const sourceLabel = { vector: '向量', bm25: 'BM25', graph: '图谱', final: '最终' };
```

### 9.5 LoadTestConfig 阈值

```java
// 场景 A 检索 P95 阈值：双路 500ms → 三路 600ms
aP95ThresholdMs = 600;  // 原 500
```

§18.4 文档同步更新。

---

## 10. 多跳集与评估接线

### 10.1 分类取舍

**推荐：`QACategory` 增 `MULTI_HOP` 枚举值**（非独立文件）

理由：
- Golden 数据集已有 glob 扫描 + 分类路由 + 指标聚合全套基建
- 独立文件需新建加载器 + 独立门禁逻辑，重复建设
- `MULTI_HOP` 与现有 `MULTI_DOC` 正交（多跳强调实体链推理，多文档强调跨文档聚合）
- `golden/multihop/` 子目录存放，文件命名 `mh-001.json` ~ `mh-030.json`

### 10.2 多跳用例结构

```json
{
  "id": "mh-001",
  "category": "MULTI_HOP",
  "question": "A 公司的 CTO 曾在哪所大学任教？",
  "expectedKeywords": ["大学名"],
  "expectedAnswer": "...",
  "expectedChunkIds": ["chunk-a", "chunk-b", "chunk-c"],
  "expectedDocs": ["company-a.pdf", "faculty-list.pdf"],
  "expectedEntityChain": ["A公司", "CTO", "人名", "大学"],
  "attackType": null,
  "questionEncoding": null,
  "questionSha256": "..."
}
```

`expectedEntityChain`：标注多跳路径的实体链（ Judge 评分时消费）。

### 10.3 草稿工具形态

`--eval.draft-multihop`（`MultiHopDraftRunner`，沿用 `AnswerDraftRunner` 先例）：

1. 扫描 `MULTI_HOP` 分类用例（`expectedAnswer` 未标注）
2. 真值材料：`expectedEntityChain` 实体 → `GraphGateway` 查图路径 → PG chunk 原文
3. 起草走 Judge 模型（qwen3.7-plus，temperature 0）
4. 产出双通道：`target/multihop-drafts.json` + `target/multihop-drafts.md`
5. 人工审定回写 `golden/multihop/*.json`

### 10.4 多跳准确率 >80% 判据

沿用既有 Judge 管道（`EvalRunner` → `JudgePrompts` → qwen3.7-plus）：

- **Answer Correctness**：Judge 评分 ≥0.7 为通过（1-5 分制归一化）
- **Entity Chain Coverage**：`expectedEntityChain` 实体在回答中被提及的比例 ≥80%
- 门禁：`MULTI_HOP` 分类通过率 ≥80%

`MultiHopMetrics` 新增：
```java
double accuracy = passedCount / totalCount;  // ≥0.80 门禁
double entityChainCoverage = avg(entityHits / expectedChainLength);
```

---

## 11. 风险与核验清单

### 11.1 落码前必须源码级核验

| 序号 | 核验项 | 风险 | 核验方法 |
|------|--------|------|----------|
| 1 | Boot 4.1 `spring-boot-neo4j` autoconfig 形态 | 自动装配 `Driver` Bean 的条件/属性名可能与 3.x 不同 | `ApplicationContextRunner` 实证 + 官方 4.1 文档 |
| 2 | Neo4j Community 5.x 向量索引 Cypher DDL | `CREATE VECTOR INDEX` 语法/参数在 Community 版可能受限 | Neo4j 5.x 官方文档 + 第二台 ECS 实跑验证 |
| 3 | Neo4j Java Driver 5.x API | `Session.run` / `Result` API 是否有 breaking change | 官方 JavaDoc + 单测 mock |
| 4 | `neo4j-java-driver` 与 Spring Boot 4.1 兼容性 | Driver Bean 生命周期管理 | 核验 starter 源码或 autoconfig 类 |
| 5 | Redisson `RRateLimiter` 在 kb-etl 模块可用性 | Redisson 依赖在 kb-infrastructure，kb-etl 需传递 | 核验 pom 依赖链 |

### 11.2 已知坑位规避

| 坑位 | 风险 | 规避 |
|------|------|------|
| ⑭ 跨厂商 Prompt 屏障 | 抽取走 qwen3.7-plus（百炼），与主模型 DeepSeek V4 异构 | 抽取独立 ChatModel Bean，不经 SmartRoutingChatModel |
| ⑮ qwen 商业版默认开思考 | 抽取调用 20-60s/次 | `enable_thinking: false` 显式钉死 |
| ㉘ spring-boot:run 静默跑旧 jar | kb-infrastructure 改动未 install | 开发期 `mvn install -pl kb-infrastructure -am` |
| ⑬ Jackson 3 命名空间 | `ExtractionResult` record 的 `@JsonProperty` 须 `tools.jackson` | 落码时核验 import |

### 11.3 依赖风险

| 风险 | 缓解 |
|------|------|
| Neo4j Community GPLv3 | 内部部署合规；分发场景另议（CLAUDE.md 已登记） |
| 第二台 ECS 未到位 | 批1 开工前置条件；未到位时本地 Docker Neo4j 开发 |
| qwen3.7-plus 百炼端点 RPM 限制 | 令牌桶限流 + 信号量控制并发 |
| 图数据膨胀 | 文档删除时孤儿清扫 + 定期审计（运维脚本） |

---

## 12. E2E 交付步骤大纲

### 12.1 用户需提供

| 序号 | 信息 | 用途 |
|------|------|------|
| U1 | Neo4j Community 版本号 | 核验向量索引 DDL 兼容性 |
| U2 | 第二台 ECS bolt 地址 + 端口 | `NEO4J_URI` 配置 |
| U3 | Neo4j 初始密码 | `NEO4J_PASSWORD` 配置 |
| U4 | Neo4j 是否已启动 + `neo4j` 用户密码 | Schema 初始化验证 |

### 12.2 E2E 步骤

1. **环境准备**
   - 用户填 `infra/.env` 的 `NEO4J_*` 键
   - `infra/scripts/neo4j-backup.sh` 赋权 `chmod +x`

2. **启动验证**
   - `mvn clean install -DskipTests` → `java -jar kb-api/target/*.jar`
   - 日志确认 `GraphSchemaInitializer` 执行成功（Cypher DDL 幂等）
   - `rag.graph.enabled=true` 设环境变量或 application 覆盖

3. **抽取验证**
   - 上传测试文档 → 等待 ETL COMPLETED
   - Neo4j Browser 查 `MATCH (e:Entity) RETURN count(e)` > 0
   - `kb_document.graph_status = COMPLETED`

4. **三路检索验证**
   - Debug 检索台发多跳查询 → TRACE 含 `"graph"` 路
   - `graph_rank` 出现在命中元数据

5. **降级验证**
   - `rag.graph.enabled=false` 重启
   - 同一查询 → TRACE 无 `"graph"` 路 → 结果与开关前双路一致

6. **回填验证**
   - Admin 运维中心 → 图回填任务 → 存量文档 `graph_status` 批量 COMPLETED

7. **多跳评估**
   - `mvn spring-boot:run -pl kb-eval -Dspring-boot.run.arguments=--eval.draft-multihop`
   - 审定回写 → `mvn spring-boot:run -pl kb-eval` → 多跳准确率 ≥80%

8. **压测**
   - `mvn gatling:test -pl kb-loadtest` → 场景 A P95 ≤600ms

### 12.3 文档回写清单

| 文档 | 章节 | 内容 |
|------|------|------|
| 10 章 | §10.4 | RRF N 路泛化设计 |
| 10 章 | §10.9 | GraphRAG 三路融合（新增） |
| 13 章 | §13.3 | 指标登记表（6 项新增） |
| 17 章 | §17.5 | 灾备最小集（Neo4j 备份） |
| 18 章 | §18.4 | 压测阈值（P95 500→600） |
| CLAUDE.md | 当前实现要点 | GraphRAG 架构事实 |
| 用户侧清单 | G1/G2 | Neo4j 部署 + 回填操作 |

---

## 附录 A：模块依赖图

```
kb-domain (V2 migration + GraphStatus enum)
    ↑
kb-infrastructure (Neo4jConfig + GraphGateway + GraphSchemaInitializer)
    ↑
kb-etl (EntityExtractor + GraphExtractionService + GraphExtractionPublisher)
    ↑
kb-api (DocumentService 接线 COMPLETED 帧)
    ↑
kb-admin (GraphBackfillService + RedisGraphTaskStore + AdminController)

kb-ai-core (GraphDocumentRetriever + RrfFusion N 路 + HybridDocumentRetriever 三路)
    ↑
kb-eval (MultiHopDraftRunner + MULTI_HOP 分类 + MultiHopMetrics)
```

## 附录 B：批间依赖

```
批1（核心基建）→ 批2（接线集成）→ 批3（生命周期）→ 批4（评估）→ 批5（收口）
```

批1/批2 有强依赖（GraphGateway → GraphDocumentRetriever）；批3 可与批2 部分并行（回填任务独立）；批4 依赖批2 三路可运行。
