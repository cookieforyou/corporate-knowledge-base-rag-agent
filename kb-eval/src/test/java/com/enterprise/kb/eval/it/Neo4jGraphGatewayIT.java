package com.enterprise.kb.eval.it;

import com.enterprise.kb.infrastructure.graph.GraphGateway;
import com.enterprise.kb.infrastructure.graph.GraphIds;
import com.enterprise.kb.infrastructure.graph.GraphRecords;
import com.enterprise.kb.infrastructure.graph.Neo4jGraphGateway;
import com.enterprise.kb.infrastructure.graph.Neo4jProperties;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Neo4j 图谱网关真跑集成测试（Phase5簇④ 四轮热修补防）。
 *
 * <p>背景：图路展开查询的列表推导式曾写成集合记法形态（{@code [{…} | x IN list]}），
 * Cypher 正确语序为 {@code [x IN list | …]}——语法错仅在真库解析期暴露，
 * mock Driver 单测全线盲视，用户侧 E2E（draft-multihop）才命中「图路降级空路」。
 * 本 IT 以真 Neo4j 容器实跑网关全表面（幂等 Schema / 写 / 读双形态 / 租户隔离 /
 * 软删联动 / 链采样 / 幂等重写 / 删除清引用），作为 Cypher 语法与语义的实跑守卫。
 *
 * <p>镜像钉生产同版本（用户侧实证 Neo4j 5.26.29 Community），解析器行为等价。
 * 不引 Spring 上下文——网关为纯类，直构造即测（与生产手工装配同形）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Neo4jGraphGatewayIT {

    private static final String TENANT = "t-it";
    private static final String OTHER_TENANT = "t-other";
    private static final String DOC_ID = "doc-it-1";
    private static final String CHUNK_1 = "chunk-it-1";
    private static final String CHUNK_2 = "chunk-it-2";
    private static final String CHUNK_3 = "chunk-it-3";
    private static final String ALPHA = "alpha";
    private static final String BETA = "beta";
    private static final String GAMMA = "gamma";
    private static final String ALPHA_ID = GraphIds.entityId(TENANT, ALPHA, "CONCEPT");
    private static final String BETA_ID = GraphIds.entityId(TENANT, BETA, "CONCEPT");
    private static final String GAMMA_ID = GraphIds.entityId(TENANT, GAMMA, "CONCEPT");

    // ── v2.85 批1 夹具常量 ────────────────────────────────────────────

    /** 孤儿关系回归租户：doc-c 先写（共享 X/Y 无关系），doc-a 后写（X/Y + 关系） */
    private static final String GC_TENANT = "t-gc";
    private static final String GC_DOC_A = "doc-gc-a";
    private static final String GC_DOC_C = "doc-gc-c";
    private static final String GC_CHUNK_A = "chunk-gc-a";
    private static final String GC_CHUNK_C = "chunk-gc-c";
    private static final String GC_X_ID = GraphIds.entityId(GC_TENANT, "gc-x", "CONCEPT");
    private static final String GC_Y_ID = GraphIds.entityId(GC_TENANT, "gc-y", "CONCEPT");

    // ── v2.85 批2 夹具常量（索引 / 维度守卫 / 描述策略）─────────────

    /** 描述策略夹具：同实体三文档，描述长度递变（长 → 空 → 更长） */
    private static final String DESC_TENANT = "t-desc";
    private static final String DESC_DOC_A = "doc-desc-a";
    private static final String DESC_DOC_B = "doc-desc-b";
    private static final String DESC_DOC_C = "doc-desc-c";
    private static final String DESC_CHUNK_A = "chunk-desc-a";
    private static final String DESC_CHUNK_B = "chunk-desc-b";
    private static final String DESC_CHUNK_C = "chunk-desc-c";
    private static final String DESC_ENTITY = GraphIds.entityId(DESC_TENANT, "desc-entity", "CONCEPT");
    private static final String DESC_LONG = "信息量充分的长描述：含上下文、限定条件与关键属性";
    private static final String DESC_LONGER = DESC_LONG + "，并补充适用范围、生效时间与责任主体";

    /** 关系描述策略夹具：同关系两文档，长描述先写、短描述后写 */
    private static final String REL_DESC_TENANT = "t-rel-desc";
    private static final String REL_DESC_DOC_A = "doc-rel-desc-a";
    private static final String REL_DESC_DOC_B = "doc-rel-desc-b";
    private static final String REL_DESC_CHUNK_A = "chunk-rel-desc-a";
    private static final String REL_DESC_CHUNK_B = "chunk-rel-desc-b";
    private static final String REL_DESC_X = GraphIds.entityId(REL_DESC_TENANT, "rel-desc-x", "CONCEPT");
    private static final String REL_DESC_Y = GraphIds.entityId(REL_DESC_TENANT, "rel-desc-y", "CONCEPT");
    private static final String REL_DESC_LONG = "关系的长描述：桥接两个实体并说明方向、条件与生效范围";

    /** 名额挤占夹具：本租户 1 个 0.995 分种子 vs 他租户 6 个 1.0 分候选；冷租户无任何图数据 */
    private static final String MINE_TENANT = "t-mine";
    private static final String NOISY_TENANT = "t-noisy";
    private static final String COLD_TENANT = "t-cold-none";
    private static final String NOISY_DOC = "doc-noisy";
    private static final String MINE_DOC = "doc-mine";
    private static final String MINE_CHUNK = "chunk-mine";
    private static final String MINE_ENTITY = GraphIds.entityId(MINE_TENANT, "mine-entity", "CONCEPT");

    /** 1024 维单位基向量——Neo4j 余弦得分归一化形态 (1+cos)/2：alpha↔查询=1.0，
     * 与 beta/gamma 正交=0.5（IT 首跑实证 + 官方口径），故种子阈值取 0.75 排除正交向量 */
    private static final float[] QUERY_ALPHA = unitVector(0);
    private static final double SEED_THRESHOLD = 0.75;

    private static final Neo4jContainer NEO4J = new Neo4jContainer(
            DockerImageName.parse("neo4j:5.26.29"))   // 钉生产同版本（用户侧实证形态）
        .withoutAuthentication();

    private static Driver driver;
    private static Neo4jGraphGateway gateway;

    // 静态块显式启动（与 AbstractAdvisorChainIT 容器纪律同形：跨类单例 + 友好失败面）
    static {
        try {
            NEO4J.start();
        } catch (Exception e) {
            ExceptionInInitializerError err = new ExceptionInInitializerError(
                "Docker 不可用，集成测试无法执行——请启动 Docker Desktop 后重试；" +
                "CI 无 Docker 环境请加 -DskipITs 跳过。Root cause: " + e.getMessage());
            err.initCause(e);
            throw err;
        }
    }

    @BeforeAll
    static void setUp() {
        driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.none());
        Neo4jProperties properties = new Neo4jProperties();
        properties.setQueryTimeoutSeconds(30);
        gateway = new Neo4jGraphGateway(driver, properties);
        gateway.ensureSchema();
        // 向量索引异步创建——等全部索引 ONLINE 再写数据/查询（生产启动期同序）
        try (Session session = driver.session()) {
            session.run("CALL db.awaitIndexes(120)").consume();
        }
        gateway.replaceDocumentGraph(TENANT, DOC_ID, chunks(), entities(), relations());
    }

    @Test
    @Order(1)
    void schemaIdempotentAndWriteLandsFullGraph() {
        gateway.ensureSchema();   // 二次幂等（生产启动期 + 手工重入场景）
        GraphGateway.GraphCounts counts = gateway.countByTenant(TENANT);
        assertThat(counts.entities()).as("三实体落图").isEqualTo(3);
        assertThat(counts.relations()).as("两关系落图").isEqualTo(2);
        assertThat(counts.chunkAnchors()).as("三锚点落图").isEqualTo(3);
    }

    @Test
    @Order(2)
    void expansionRetrievalReturnsSeedAndOneHopNeighborChunks() {
        // 向量索引写入后可见性存在短暂异步窗口——轮询至命中稳定
        List<GraphRecords.GraphChunkHit> hits = Awaitility.await()
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofMillis(500))
            .until(() -> gateway.retrieveChunks(TENANT, QUERY_ALPHA, 5, 40, SEED_THRESHOLD,true, 10),
                   h -> h.size() == 2);
        assertThat(hits).hasSize(2);
        GraphRecords.GraphChunkHit seedHit = hits.get(0);
        assertThat(seedHit.chunkId()).as("种子 chunk 按贡献分降序居首").isEqualTo(CHUNK_1);
        assertThat(seedHit.hop()).isZero();
        assertThat(seedHit.score()).isCloseTo(1.0, within(0.01));
        assertThat(seedHit.entityNames()).contains(ALPHA);
        GraphRecords.GraphChunkHit neighborHit = hits.get(1);
        assertThat(neighborHit.chunkId()).as("1 跳邻居经 MENTIONS 反查可达").isEqualTo(CHUNK_2);
        assertThat(neighborHit.hop()).isEqualTo(1);
        assertThat(neighborHit.score()).as("邻居贡献 = 种子分 × 0.5").isCloseTo(0.5, within(0.01));
        assertThat(neighborHit.entityNames()).contains(BETA);
    }

    @Test
    @Order(3)
    void seedsOnlyRetrievalSkipsNeighborChunks() {
        List<GraphRecords.GraphChunkHit> hits =
            gateway.retrieveChunks(TENANT, QUERY_ALPHA, 5, 40, SEED_THRESHOLD,false, 10);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).chunkId()).isEqualTo(CHUNK_1);
        assertThat(hits.get(0).hop()).isZero();
    }

    @Test
    @Order(4)
    void otherTenantRetrievesNothing() {
        assertThat(gateway.retrieveChunks(OTHER_TENANT, QUERY_ALPHA, 5, 40, SEED_THRESHOLD,true, 10))
            .as("跨租户读零触达（fail-closed 读路径）")
            .isEmpty();
        assertThat(gateway.countByTenant(OTHER_TENANT).entities()).isZero();
    }

    @Test
    @Order(5)
    void softDeletedChunkExcludedAndRestorable() {
        gateway.setChunksDeleted(TENANT, List.of(CHUNK_1), true);
        List<GraphRecords.GraphChunkHit> hits =
            gateway.retrieveChunks(TENANT, QUERY_ALPHA, 5, 40, SEED_THRESHOLD,true, 10);
        assertThat(hits).extracting(GraphRecords.GraphChunkHit::chunkId)
            .as("软删锚点不参与图路检索")
            .doesNotContain(CHUNK_1);
        gateway.setChunksDeleted(TENANT, List.of(CHUNK_1), false);
        assertThat(gateway.retrieveChunks(TENANT, QUERY_ALPHA, 5, 40, SEED_THRESHOLD,true, 10))
            .extracting(GraphRecords.GraphChunkHit::chunkId)
            .as("恢复后重新可见")
            .contains(CHUNK_1);
    }

    @Test
    @Order(6)
    void chainSamplingYieldsTwoHopBridgeMaterial() {
        List<GraphRecords.EntityChainSample> samples = gateway.sampleEntityChains(TENANT, 10);
        assertThat(samples).hasSize(1);
        GraphRecords.EntityChainSample sample = samples.get(0);
        assertThat(sample.entityNames()).containsExactly(ALPHA, BETA, GAMMA);
        assertThat(sample.chunkIds())
            .as("链首/链尾关联存活 chunk 均在场（出题真值材料）")
            .contains(CHUNK_1, CHUNK_3);
    }

    @Test
    @Order(7)
    void idempotentRewriteConvergesWithoutResidue() {
        gateway.replaceDocumentGraph(TENANT, DOC_ID, chunks(), entities(), relations());
        GraphGateway.GraphCounts counts = gateway.countByTenant(TENANT);
        assertThat(counts.entities()).as("幂等重写不产生残留实体").isEqualTo(3);
        assertThat(counts.relations()).isEqualTo(2);
        assertThat(counts.chunkAnchors()).isEqualTo(3);
    }

    @Test
    @Order(8)
    void removeDocumentCleansAllReferences() {
        gateway.removeDocument(TENANT, DOC_ID);
        GraphGateway.GraphCounts counts = gateway.countByTenant(TENANT);
        assertThat(counts.entities()).as("引用归零实体被孤儿清扫").isZero();
        assertThat(counts.relations()).isZero();
        assertThat(counts.chunkAnchors()).isZero();
    }

    // ── v2.85 批1 回归夹具 ────────────────────────────────────────────

    /**
     * 孤儿关系回归（v2.85）：两文档共享实体 X/Y，关系仅归 doc-a。
     * 重写 doc-a 时不给关系（限流跳过 chunk / LLM 抖动）→ 关系引用归零而
     * <b>无孤儿实体</b>（X/Y 仍被 doc-c 引用）——正是串联 GC 空结果短路的触发条件。
     */
    @Test
    @Order(9)
    void orphanRelationCollectedWhenNoOrphanEntityExists() {
        gateway.replaceDocumentGraph(GC_TENANT, GC_DOC_C, gcChunks(GC_CHUNK_C),
            gcEntities(GC_CHUNK_C), List.of());
        gateway.replaceDocumentGraph(GC_TENANT, GC_DOC_A, gcChunks(GC_CHUNK_A),
            gcEntities(GC_CHUNK_A), gcRelation(GC_CHUNK_A));
        assertThat(gateway.countByTenant(GC_TENANT).relations()).as("夹具：关系已落图").isEqualTo(1);

        gateway.replaceDocumentGraph(GC_TENANT, GC_DOC_A, gcChunks(GC_CHUNK_A),
            gcEntities(GC_CHUNK_A), List.of());

        GraphGateway.GraphCounts counts = gateway.countByTenant(GC_TENANT);
        assertThat(counts.entities()).as("实体仍被 doc-c 引用 → 无孤儿实体（短路触发条件）").isEqualTo(2);
        assertThat(counts.chunkAnchors()).as("两文档锚点在场").isEqualTo(2);
        assertThat(counts.relations())
            .as("空引用关系必须被关系段独立清扫——串联形态在此短路残留（修复前本断言必挂）")
            .isZero();
    }

    /**
     * 跨租户名额挤占回归（v2.85）：Neo4j 5.26 索引无租户内过滤（计划实证
     * ProcedureCall → Filter 后过滤），他租户 6 条 1.0 分候选占满缺省 5 个名额，
     * 本租户 0.995 分候选在不过取窗口内完全不可见。
     */
    @Test
    @Order(10)
    void crossTenantSlotCompetitionResolvedByIndexOverFetch() {
        gateway.replaceDocumentGraph(NOISY_TENANT, NOISY_DOC, List.of(), noisyEntities(), List.of());
        gateway.replaceDocumentGraph(MINE_TENANT, MINE_DOC, mineChunks(), mineEntities(), List.of());

        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
            .until(() -> gateway.retrieveChunks(MINE_TENANT, QUERY_ALPHA, 5, 40, SEED_THRESHOLD, false, 10),
                hits -> hits.size() == 1);

        assertThat(gateway.retrieveChunks(MINE_TENANT, QUERY_ALPHA, 5, 5, SEED_THRESHOLD, false, 10))
            .as("不过取（窗口 = 种子上限 5）名额被他租户占满 → 零召回（饿死复原）")
            .isEmpty();
        assertThat(gateway.retrieveChunks(MINE_TENANT, QUERY_ALPHA, 5, 40, SEED_THRESHOLD, false, 10))
            .as("过取 8 倍后本租户种子进入窗口 → chunk 反查命中")
            .singleElement()
            .satisfies(hit -> {
                assertThat(hit.chunkId()).isEqualTo(MINE_CHUNK);
                assertThat(hit.score()).as("0.995 分（0.99/0.14 夹角向量）").isCloseTo(0.995, within(0.005));
            });
        assertThat(gateway.retrieveChunks(MINE_TENANT, QUERY_ALPHA, 5, 40, SEED_THRESHOLD, true, 10))
            .as("展开形态同样可达（无关系时邻居集为空，退化为种子自身）")
            .singleElement()
            .satisfies(hit -> assertThat(hit.hop()).isZero());
    }

    /** 空召回归因回归（v2.85）：窗口读数区分「饿死」与「冷租户」与「锚点缺口」 */
    @Test
    @Order(11)
    void diagnosticsSeparateStarvationFromColdTenantAndAnchorGap() {
        GraphRecords.GraphRetrievalDiagnostics starved = Awaitility.await()
            .atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
            .until(() -> gateway.diagnoseRetrieval(MINE_TENANT, QUERY_ALPHA, 5, SEED_THRESHOLD),
                d -> d.indexCandidates() > 0);

        assertThat(starved.tenantSeeds()).as("窗口 = 5 被他租户占满").isZero();
        assertThat(starved.starved()).as("窗口有候选 + 本租户零种子 + 本租户图谱有数据 = 饿死").isTrue();

        GraphRecords.GraphRetrievalDiagnostics wide =
            gateway.diagnoseRetrieval(MINE_TENANT, QUERY_ALPHA, 40, SEED_THRESHOLD);
        assertThat(wide.tenantSeeds()).as("过取窗口内本租户种子在场").isEqualTo(1);
        assertThat(wide.starved()).isFalse();

        GraphRecords.GraphRetrievalDiagnostics cold =
            gateway.diagnoseRetrieval(COLD_TENANT, QUERY_ALPHA, 40, SEED_THRESHOLD);
        assertThat(cold.indexCandidates()).as("窗口内确有他租户候选").isGreaterThan(0);
        assertThat(cold.coldTenant()).as("本租户图谱无实体 → 冷租户").isTrue();
        assertThat(cold.starved()).as("冷租户不计饿死（防指标被「图里没有」污染）").isFalse();
    }

    // ── v2.85 批2 用例（索引 / 维度守卫 / 描述策略）─────────────────

    /** 索引补齐回归（v3.00）：Chunk.tenant_id（锚点统计不再全库扫标签）+ RELATED_TO.tenant_id（租户域清扫走索引） */
    @Test
    @Order(12)
    void schemaCreatesTenantIndexesForChunkAndRelation() {
        Map<String, String> entityTypes = new HashMap<>();
        try (Session session = driver.session()) {
            session.run("SHOW INDEXES YIELD name, entityType "
                    + "WHERE name IN ['kb_chunk_tenant', 'kb_relation_tenant'] RETURN name, entityType")
                .forEachRemaining(record -> entityTypes.put(
                    record.get("name").asString(), record.get("entityType").asString()));
        }
        assertThat(entityTypes)
            .as("缺前者 countByTenant 全库扫 Chunk 标签；缺后者租户域清扫全库扫关系类型（计划实证）")
            .containsEntry("kb_chunk_tenant", "NODE")
            .containsEntry("kb_relation_tenant", "RELATIONSHIP");
    }

    /** 实体描述与嵌入「信息量更大者胜」（v3.00）：空/短描述不得降级已存长描述与其描述向量 */
    @Test
    @Order(13)
    void entityDescriptionAndEmbeddingNotDowngradedByShorterDescription() {
        gateway.replaceDocumentGraph(DESC_TENANT, DESC_DOC_A, descChunks(DESC_CHUNK_A),
            descEntity(DESC_LONG, unitVector(20), DESC_CHUNK_A), List.of());
        // 空描述后写：原「取最新」会把长描述冲成 ""，并把向量降级为名称向量（embedding 是检索键）
        gateway.replaceDocumentGraph(DESC_TENANT, DESC_DOC_B, descChunks(DESC_CHUNK_B),
            descEntity("", unitVector(21), DESC_CHUNK_B), List.of());

        try (Session session = driver.session()) {
            Record afterEmpty = entityRecord(session);
            assertThat(afterEmpty.get("description").asString())
                .as("空描述不得冲掉长描述").isEqualTo(DESC_LONG);
            assertThat(afterEmpty.get("embedding").asList(Value::asDouble).get(20))
                .as("向量与描述配对保留（未被名称向量降级覆盖）").isEqualTo(1.0);
            assertThat(afterEmpty.get("embedding").asList(Value::asDouble).get(21))
                .as("后写文档的向量未夺位").isEqualTo(0.0);
        }

        // 更长描述后写：应当胜出（策略 = 较长者胜，非「首见保留」），且描述与向量同批更新
        gateway.replaceDocumentGraph(DESC_TENANT, DESC_DOC_C, descChunks(DESC_CHUNK_C),
            descEntity(DESC_LONGER, unitVector(22), DESC_CHUNK_C), List.of());

        try (Session session = driver.session()) {
            Record afterLonger = entityRecord(session);
            assertThat(afterLonger.get("description").asString()).isEqualTo(DESC_LONGER);
            assertThat(afterLonger.get("embedding").asList(Value::asDouble).get(22))
                .as("描述与描述向量同批更新（配对不错位）").isEqualTo(1.0);
            assertThat(afterLonger.get("embedding").asList(Value::asDouble).get(20))
                .as("旧描述向量已被替换").isEqualTo(0.0);
            assertThat(afterLonger.get("doc_ids").asList(Value::asString))
                .as("溯源 doc_ids 取并集（三文档）")
                .containsExactlyInAnyOrder(DESC_DOC_A, DESC_DOC_B, DESC_DOC_C);
        }
    }

    /** 关系描述策略（v3.00）：短描述后写不得覆盖长描述；doc_ids 并集不变 */
    @Test
    @Order(14)
    void relationDescriptionNotOverwrittenByShorterOne() {
        gateway.replaceDocumentGraph(REL_DESC_TENANT, REL_DESC_DOC_A, descChunks(REL_DESC_CHUNK_A),
            relDescEntities(REL_DESC_CHUNK_A),
            List.of(new GraphRecords.RelationWrite(REL_DESC_X, REL_DESC_Y, "RELATED",
                REL_DESC_LONG, List.of(REL_DESC_CHUNK_A))));
        gateway.replaceDocumentGraph(REL_DESC_TENANT, REL_DESC_DOC_B, descChunks(REL_DESC_CHUNK_B),
            relDescEntities(REL_DESC_CHUNK_B),
            List.of(new GraphRecords.RelationWrite(REL_DESC_X, REL_DESC_Y, "RELATED",
                "短", List.of(REL_DESC_CHUNK_B))));

        try (Session session = driver.session()) {
            Record record = session.run(
                    "MATCH (:Entity {id: $s})-[r:RELATED_TO]->(:Entity {id: $t}) "
                        + "RETURN r.description AS description, size(r.doc_ids) AS docCount",
                    Map.of("s", REL_DESC_X, "t", REL_DESC_Y)).single();
            assertThat(record.get("description").asString())
                .as("短描述不得互覆长描述（原 ON MATCH 直接覆盖）").isEqualTo(REL_DESC_LONG);
            assertThat(record.get("docCount").asLong()).as("两文档引用合并").isEqualTo(2L);
        }
    }

    /** 写入侧维度守卫（v3.00）：异维实体写前快失败，不落图、不进事务 */
    @Test
    @Order(15)
    void mismatchedEmbeddingRejectedBeforeTouchingGraph() {
        GraphGateway.GraphCounts before = gateway.countByTenant(GC_TENANT);

        assertThatThrownBy(() -> gateway.replaceDocumentGraph(GC_TENANT, "doc-bad-dim", descChunks("chunk-bad-dim"),
            List.of(new GraphRecords.EntityWrite(GraphIds.entityId(GC_TENANT, "bad-dim", "CONCEPT"),
                "bad-dim", "CONCEPT", "异维实体", new float[768], List.of("chunk-bad-dim"))), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("维度不符");

        GraphGateway.GraphCounts after = gateway.countByTenant(GC_TENANT);
        assertThat(after.entities()).as("快失败：未落实体").isEqualTo(before.entities());
        assertThat(after.chunkAnchors()).as("快失败：未落锚点").isEqualTo(before.chunkAnchors());
    }

    // ── 夹具 ──────────────────────────────────────────────────────────

    private static List<GraphRecords.ChunkAnchor> chunks() {
        return List.of(
            new GraphRecords.ChunkAnchor(CHUNK_1, 0),
            new GraphRecords.ChunkAnchor(CHUNK_2, 1),
            new GraphRecords.ChunkAnchor(CHUNK_3, 2));
    }

    private static List<GraphRecords.EntityWrite> entities() {
        return List.of(
            new GraphRecords.EntityWrite(ALPHA_ID, ALPHA, "CONCEPT", "种子实体",
                unitVector(0), List.of(CHUNK_1)),
            new GraphRecords.EntityWrite(BETA_ID, BETA, "CONCEPT", "一跳邻居",
                unitVector(1), List.of(CHUNK_2)),
            new GraphRecords.EntityWrite(GAMMA_ID, GAMMA, "CONCEPT", "二跳链尾",
                unitVector(2), List.of(CHUNK_3)));
    }

    private static List<GraphRecords.RelationWrite> relations() {
        return List.of(
            new GraphRecords.RelationWrite(ALPHA_ID, BETA_ID, "RELATED", "桥接关系",
                List.of(CHUNK_1, CHUNK_2)),
            new GraphRecords.RelationWrite(BETA_ID, GAMMA_ID, "RELATED", "桥接关系",
                List.of(CHUNK_2, CHUNK_3)));
    }

    // ── v2.85 批1 夹具构造 ────────────────────────────────────────────

    private static List<GraphRecords.ChunkAnchor> gcChunks(String chunkId) {
        return List.of(new GraphRecords.ChunkAnchor(chunkId, 0));
    }

    /** 共享实体 X/Y（两文档各写一次）——向量取正交基（0.5 分，不参与种子竞争） */
    private static List<GraphRecords.EntityWrite> gcEntities(String chunkId) {
        return List.of(
            new GraphRecords.EntityWrite(GC_X_ID, "gc-x", "CONCEPT", "共享实体 X",
                unitVector(10), List.of(chunkId)),
            new GraphRecords.EntityWrite(GC_Y_ID, "gc-y", "CONCEPT", "共享实体 Y",
                unitVector(11), List.of(chunkId)));
    }

    private static List<GraphRecords.RelationWrite> gcRelation(String chunkId) {
        return List.of(new GraphRecords.RelationWrite(GC_X_ID, GC_Y_ID, "RELATED",
            "仅归 doc-a 的关系", List.of(chunkId)));
    }

    /** 他租户 6 条 1.0 分候选（占满缺省 5 个索引名额）——不建锚点，只参与名额竞争 */
    private static List<GraphRecords.EntityWrite> noisyEntities() {
        return IntStream.range(0, 6)
            .mapToObj(i -> new GraphRecords.EntityWrite(
                GraphIds.entityId(NOISY_TENANT, "noisy-" + i, "CONCEPT"),
                "noisy-" + i, "CONCEPT", "他租户近邻", QUERY_ALPHA.clone(), List.of()))
            .toList();
    }

    private static List<GraphRecords.ChunkAnchor> mineChunks() {
        return List.of(new GraphRecords.ChunkAnchor(MINE_CHUNK, 0));
    }

    /** 本租户种子：0.995 分（刻意排在 1.0 分候选之后，不过取即不可见） */
    private static List<GraphRecords.EntityWrite> mineEntities() {
        return List.of(new GraphRecords.EntityWrite(MINE_ENTITY, "mine-entity", "CONCEPT",
            "本租户种子", rotatedQueryVector(), List.of(MINE_CHUNK)));
    }

    /** 与 QUERY_ALPHA 夹角 8° 的向量：归一化余弦分 ≈ 0.995（(1+cos)/2） */
    private static float[] rotatedQueryVector() {
        float[] vector = new float[GraphGateway.ENTITY_EMBEDDING_DIMENSIONS];
        vector[0] = 0.99f;
        vector[1] = 0.14f;
        return vector;
    }

    // ── v2.85 批2 夹具构造 ────────────────────────────────────────────

    private static List<GraphRecords.ChunkAnchor> descChunks(String chunkId) {
        return List.of(new GraphRecords.ChunkAnchor(chunkId, 0));
    }

    private static List<GraphRecords.EntityWrite> descEntity(String description, float[] embedding,
                                                             String chunkId) {
        return List.of(new GraphRecords.EntityWrite(DESC_ENTITY, "desc-entity", "CONCEPT",
            description, embedding, List.of(chunkId)));
    }

    private static Record entityRecord(Session session) {
        return session.run(
            "MATCH (e:Entity {id: $id}) RETURN e.description AS description, "
                + "e.embedding AS embedding, e.doc_ids AS doc_ids",
            Map.of("id", DESC_ENTITY)).single();
    }

    private static List<GraphRecords.EntityWrite> relDescEntities(String chunkId) {
        return List.of(
            new GraphRecords.EntityWrite(REL_DESC_X, "rel-desc-x", "CONCEPT", "关系描述夹具 X",
                unitVector(22), List.of(chunkId)),
            new GraphRecords.EntityWrite(REL_DESC_Y, "rel-desc-y", "CONCEPT", "关系描述夹具 Y",
                unitVector(23), List.of(chunkId)));
    }

    private static float[] unitVector(int axis) {
        float[] vector = new float[GraphGateway.ENTITY_EMBEDDING_DIMENSIONS];
        vector[axis] = 1.0f;
        return vector;
    }
}
