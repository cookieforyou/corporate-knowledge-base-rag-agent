package com.enterprise.kb.infrastructure.graph;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Neo4jGraphGateway 守卫单测（Phase5簇④，v3.00 扩维度契约）：空租户读路径零触达返回空、
 * 写路径快失败、查询向量/实体嵌入维度不符前置拦截——与检索侧两层 fail-closed 纪律同口径。
 */
class Neo4jGraphGatewayGuardTest {

    /** 夹具用邻域上限：远大于夹具度数 → 等价「不截断」，保持既有用例语义（v3.04） */
    private static final int NEIGHBOR_LIMIT = 1000;

    private final Driver driver = mock(Driver.class);
    private final Neo4jGraphGateway gateway = new Neo4jGraphGateway(driver, new Neo4jProperties());

    @Test
    void retrieveWithBlankTenantReturnsEmptyWithoutTouchingDriver() {
        assertThat(gateway.retrieveChunks(null, spec(new float[1024], true))).isEmpty();
        assertThat(gateway.retrieveChunks("", spec(new float[1024], true))).isEmpty();
        assertThat(gateway.retrieveChunks("t1", spec(new float[0], true)))
            .as("空向量同样零触达")
            .isEmpty();
        verifyNoInteractions(driver);
    }

    @Test
    void diagnoseWithBlankTenantOrVectorReturnsZeroWithoutTouchingDriver() {
        assertThat(gateway.diagnoseRetrieval(null, new float[1024], 40, 200, 0.7))
            .as("归因读数同守读路径 fail-closed 纪律")
            .isEqualTo(GraphRecords.GraphRetrievalDiagnostics.EMPTY);
        assertThat(gateway.diagnoseRetrieval("", new float[1024], 40, 200, 0.7).starved()).isFalse();
        assertThat(gateway.diagnoseRetrieval("t1", new float[0], 40, 200, 0.7).anchorGap()).isFalse();
        verifyNoInteractions(driver);
    }

    @Test
    void mismatchedQueryVectorDimensionFailsClosedWithoutTouchingDriver() {
        // v3.00：维度不符前置守卫（否则由 Neo4j 抛 "Index query vector has 768 dimensions…"，
        // 归因落在「图库」而非「嵌入源与索引不同源」，且错误面被单路容错吞成空路）
        assertThat(gateway.retrieveChunks("t1", spec(new float[768], true))).isEmpty();
        assertThat(gateway.diagnoseRetrieval("t1", new float[768], 40, 200, 0.7))
            .isEqualTo(GraphRecords.GraphRetrievalDiagnostics.EMPTY);
        verifyNoInteractions(driver);
    }

    @Test
    void writeWithMismatchedOrNullEmbeddingFailsClosedBeforeTransaction() {
        // v3.00：兑现接口契约「写入侧维度不符即拒绝」——真库实证异维写入不报错不落日志
        // 且节点对索引永久不可见，故必须在网关入口拦下（写前快失败，不进事务）
        assertThatThrownBy(() -> gateway.replaceDocumentGraph("t1", "doc-1", List.of(),
            List.of(entityWith(new float[768])), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("维度不符");
        assertThatThrownBy(() -> gateway.replaceDocumentGraph("t1", "doc-1", List.of(),
            List.of(entityWith(null)), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("维度不符");
        verifyNoInteractions(driver);
    }

    @Test
    void embeddingGuardBoundaryIsPinnedDimension() {
        Neo4jGraphGateway.requireEmbeddings(List.of(entityWith(new float[1024])));   // 契约内：不抛
        Neo4jGraphGateway.requireEmbeddings(List.of());                             // 空集：不抛
        assertThatThrownBy(() -> Neo4jGraphGateway.requireEmbeddings(List.of(entityWith(new float[0]))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Neo4jGraphGateway.requireEmbeddings(List.of(entityWith(new float[1536]))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** 检索规格夹具（v3.02 参数对象化）：种子上限 5 / 过取 40 / 阈值 0.7 / 候选 100 / 结果 10 */
    private static GraphRecords.GraphRetrievalSpec spec(float[] embedding, boolean expand) {
        return new GraphRecords.GraphRetrievalSpec(embedding, 5, 40, 0.7,
            expand ? GraphRecords.ExpandDirection.BOTH : GraphRecords.ExpandDirection.NONE, 100,
            NEIGHBOR_LIMIT, 10);
    }

    private static GraphRecords.EntityWrite entityWith(float[] embedding) {
        return new GraphRecords.EntityWrite("e-1", "e1", "CONCEPT", "描述", embedding, List.of("c1"));
    }

    @Test
    void writeWithBlankTenantFailsClosed() {
        assertThatThrownBy(() -> gateway.replaceDocumentGraph(
            " ", "doc-1", List.of(), List.of(), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("租户");
        assertThatThrownBy(() -> gateway.removeDocument(null, "doc-1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.countByTenant(""))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(driver);
    }

    @Test
    void writeWithBlankDocIdFailsClosed() {
        assertThatThrownBy(() -> gateway.replaceDocumentGraph("t1", null, List.of(), List.of(), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("docId");
        verifyNoInteractions(driver);
    }

    @Test
    void embeddingDimensionConstantPinned() {
        assertThat(GraphGateway.ENTITY_EMBEDDING_DIMENSIONS)
            .as("1024 维与主检索链路同源（pgvector/Milvus/语义缓存三处钉死）")
            .isEqualTo(1024);
    }

    @Test
    void retrievalDiagnosticsClassifiesEmptyCauseByDualWindowReadings() {
        // v3.03 双窗口判据：饿死 = 放大窗口内有本租户阈值内实体，却挤不进检索窗口
        assertThat(new GraphRecords.GraphRetrievalDiagnostics(6, 2, 0).starved()).isTrue();
        assertThat(new GraphRecords.GraphRetrievalDiagnostics(6, 2, 0).anchorGap()).isFalse();
        assertThat(new GraphRecords.GraphRetrievalDiagnostics(6, 2, 0).coldTenant()).isFalse();
        // 本租户实体全在阈值之外（放大窗口也看不见）→ 正常空，不计饿死（F3 复核修正点）
        assertThat(new GraphRecords.GraphRetrievalDiagnostics(6, 0, 0).starved()).isFalse();
        assertThat(new GraphRecords.GraphRetrievalDiagnostics(6, 0, 0).coldTenant()).isTrue();
        // 窗口内无候选（查询与图无关联）同样不算饿死
        assertThat(GraphRecords.GraphRetrievalDiagnostics.EMPTY.starved()).isFalse();
        // 本租户种子进了窗口却零召回 → 锚点链路缺口
        assertThat(new GraphRecords.GraphRetrievalDiagnostics(6, 2, 2).anchorGap()).isTrue();
        assertThat(new GraphRecords.GraphRetrievalDiagnostics(6, 2, 2).starved()).isFalse();
    }

    @Test
    void retrievalWindowCapsNeverDropBelowSeedLimit() {
        // v3.03 F8：兜底改写边界直断（原仅路径内联，未覆盖）
        assertThat(Neo4jGraphGateway.fetchCap(5, 40)).as("正常过取").isEqualTo(40);
        assertThat(Neo4jGraphGateway.fetchCap(5, 3)).as("过取低于种子上限 → 退化为不否过取").isEqualTo(5);
        assertThat(Neo4jGraphGateway.fetchCap(5, 0)).isEqualTo(5);
        assertThat(Neo4jGraphGateway.candidateCap(5, 100)).isEqualTo(100);
        assertThat(Neo4jGraphGateway.candidateCap(5, 3)).as("候选上限不得低于种子数（种子恒在）").isEqualTo(5);
    }

    @Test
    void neighborSamplingCapFallsBackToCandidateLimitWhenUnset() {
        // v3.04：非正值（未配置/非法）回落候选上限 = v3.02 等价上界（不引入新召回收紧）；
        // 正值一律显式生效——低于候选上限是「主动收紧单种子采样」，不得被静默抬高
        assertThat(Neo4jGraphGateway.neighborCap(100, 256)).as("缺省形态：高于候选上限").isEqualTo(256);
        assertThat(Neo4jGraphGateway.neighborCap(100, 100)).as("等于候选上限").isEqualTo(100);
        assertThat(Neo4jGraphGateway.neighborCap(100, 8)).as("显式收紧不被抬高").isEqualTo(8);
        assertThat(Neo4jGraphGateway.neighborCap(100, 1)).isEqualTo(1);
        assertThat(Neo4jGraphGateway.neighborCap(100, 0)).as("未配置 → 回落候选上限").isEqualTo(100);
        assertThat(Neo4jGraphGateway.neighborCap(100, -1)).isEqualTo(100);
    }
}
