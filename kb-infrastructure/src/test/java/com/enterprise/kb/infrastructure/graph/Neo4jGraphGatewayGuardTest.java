package com.enterprise.kb.infrastructure.graph;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Neo4jGraphGateway 租户守卫单测（簇④）：空租户读路径零触达返回空、
 * 写路径快失败——与检索侧两层 fail-closed 纪律同口径。
 */
class Neo4jGraphGatewayGuardTest {

    private final Driver driver = mock(Driver.class);
    private final Neo4jGraphGateway gateway = new Neo4jGraphGateway(driver, new Neo4jProperties());

    @Test
    void retrieveWithBlankTenantReturnsEmptyWithoutTouchingDriver() {
        assertThat(gateway.retrieveChunks(null, new float[1024], 5, 0.7, true, 10)).isEmpty();
        assertThat(gateway.retrieveChunks("", new float[1024], 5, 0.7, true, 10)).isEmpty();
        assertThat(gateway.retrieveChunks("t1", new float[0], 5, 0.7, true, 10))
            .as("空向量同样零触达")
            .isEmpty();
        verifyNoInteractions(driver);
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
}
