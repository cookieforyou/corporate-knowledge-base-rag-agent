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

    private final Driver driver = mock(Driver.class);
    private final Neo4jGraphGateway gateway = new Neo4jGraphGateway(driver, new Neo4jProperties());

    @Test
    void retrieveWithBlankTenantReturnsEmptyWithoutTouchingDriver() {
        assertThat(gateway.retrieveChunks(null, new float[1024], 5, 40, 0.7, true, 10)).isEmpty();
        assertThat(gateway.retrieveChunks("", new float[1024], 5, 40, 0.7, true, 10)).isEmpty();
        assertThat(gateway.retrieveChunks("t1", new float[0], 5, 40, 0.7, true, 10))
            .as("空向量同样零触达")
            .isEmpty();
        verifyNoInteractions(driver);
    }

    @Test
    void diagnoseWithBlankTenantOrVectorReturnsZeroWithoutTouchingDriver() {
        assertThat(gateway.diagnoseRetrieval(null, new float[1024], 40, 0.7))
            .as("归因读数同守读路径 fail-closed 纪律")
            .isEqualTo(GraphRecords.GraphRetrievalDiagnostics.EMPTY);
        assertThat(gateway.diagnoseRetrieval("", new float[1024], 40, 0.7).starved()).isFalse();
        assertThat(gateway.diagnoseRetrieval("t1", new float[0], 40, 0.7).anchorGap()).isFalse();
        verifyNoInteractions(driver);
    }

    @Test
    void mismatchedQueryVectorDimensionFailsClosedWithoutTouchingDriver() {
        // v3.00：维度不符前置守卫（否则由 Neo4j 抛 "Index query vector has 768 dimensions…"，
        // 归因落在「图库」而非「嵌入源与索引不同源」，且错误面被单路容错吞成空路）
        assertThat(gateway.retrieveChunks("t1", new float[768], 5, 40, 0.7, true, 10)).isEmpty();
        assertThat(gateway.diagnoseRetrieval("t1", new float[768], 40, 0.7))
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
    void retrievalDiagnosticsClassifiesEmptyCauseByWindowReadings() {        // 窗口内有他租户候选、本租户零种子、且本租户图谱有数据 = 饿死（过取倍数不足）
        assertThat(new GraphRecords.GraphRetrievalDiagnostics(6, 0, true).starved()).isTrue();
        assertThat(new GraphRecords.GraphRetrievalDiagnostics(6, 0, true).anchorGap()).isFalse();
        // 窗口内无候选 = 查询与图无关联（正常空），不算饿死
        assertThat(GraphRecords.GraphRetrievalDiagnostics.EMPTY.starved()).isFalse();
        // 冷租户（图谱无数据）：空召回正常，不计饿死——否则冷租户会污染饿死指标
        assertThat(new GraphRecords.GraphRetrievalDiagnostics(6, 0, false).starved()).isFalse();
        assertThat(new GraphRecords.GraphRetrievalDiagnostics(6, 0, false).coldTenant()).isTrue();
        // 本租户有阈值内种子 = 锚点链路缺口（非检索参数问题）
        assertThat(new GraphRecords.GraphRetrievalDiagnostics(6, 2, true).starved()).isFalse();
        assertThat(new GraphRecords.GraphRetrievalDiagnostics(6, 2, true).anchorGap()).isTrue();
        assertThat(new GraphRecords.GraphRetrievalDiagnostics(6, 2, true).coldTenant()).isFalse();
    }
}
