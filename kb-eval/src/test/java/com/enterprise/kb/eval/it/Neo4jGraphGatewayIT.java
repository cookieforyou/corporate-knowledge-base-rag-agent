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
import org.neo4j.driver.Session;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Neo4j 图谱网关真跑集成测试（簇④ 四轮热修补防）。
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
            .until(() -> gateway.retrieveChunks(TENANT, QUERY_ALPHA, 5, SEED_THRESHOLD,true, 10),
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
            gateway.retrieveChunks(TENANT, QUERY_ALPHA, 5, SEED_THRESHOLD,false, 10);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).chunkId()).isEqualTo(CHUNK_1);
        assertThat(hits.get(0).hop()).isZero();
    }

    @Test
    @Order(4)
    void otherTenantRetrievesNothing() {
        assertThat(gateway.retrieveChunks(OTHER_TENANT, QUERY_ALPHA, 5, SEED_THRESHOLD,true, 10))
            .as("跨租户读零触达（fail-closed 读路径）")
            .isEmpty();
        assertThat(gateway.countByTenant(OTHER_TENANT).entities()).isZero();
    }

    @Test
    @Order(5)
    void softDeletedChunkExcludedAndRestorable() {
        gateway.setChunksDeleted(TENANT, List.of(CHUNK_1), true);
        List<GraphRecords.GraphChunkHit> hits =
            gateway.retrieveChunks(TENANT, QUERY_ALPHA, 5, SEED_THRESHOLD,true, 10);
        assertThat(hits).extracting(GraphRecords.GraphChunkHit::chunkId)
            .as("软删锚点不参与图路检索")
            .doesNotContain(CHUNK_1);
        gateway.setChunksDeleted(TENANT, List.of(CHUNK_1), false);
        assertThat(gateway.retrieveChunks(TENANT, QUERY_ALPHA, 5, SEED_THRESHOLD,true, 10))
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

    private static float[] unitVector(int axis) {
        float[] vector = new float[GraphGateway.ENTITY_EMBEDDING_DIMENSIONS];
        vector[axis] = 1.0f;
        return vector;
    }
}
