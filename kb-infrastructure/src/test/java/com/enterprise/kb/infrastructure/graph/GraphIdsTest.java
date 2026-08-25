package com.enterprise.kb.infrastructure.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GraphIds 确定性 ID 派生单测（簇④）：规范化收敛 + 确定性 + 租户物理隔离。
 */
class GraphIdsTest {

    @Test
    void normalizedNameConvergesCaseAndWhitespace() {
        assertThat(GraphIds.normalizeName("  Alpha   Corp "))
            .isEqualTo(GraphIds.normalizeName("alpha corp"));
        assertThat(GraphIds.normalizeName(null)).isEmpty();
        assertThat(GraphIds.normalizeName("K8s\t集群")).isEqualTo("k8s 集群");
    }

    @Test
    void entityIdIsDeterministicAcrossInvocations() {
        String first = GraphIds.entityId("t1", "Alpha Corp", "ORG");
        String second = GraphIds.entityId("t1", "Alpha Corp", "ORG");
        assertThat(first).isEqualTo(second).isNotBlank();
    }

    @Test
    void entityIdConvergesNameVariants() {
        assertThat(GraphIds.entityId("t1", "Alpha Corp", "ORG"))
            .as("大小写/空白异写收敛同节点（合并语义）")
            .isEqualTo(GraphIds.entityId("t1", "alpha   corp", "org"));
    }

    @Test
    void entityIdIsolatesTenants() {
        assertThat(GraphIds.entityId("t1", "Alpha Corp", "ORG"))
            .as("跨租户同名实体物理隔离（ID 含租户域）")
            .isNotEqualTo(GraphIds.entityId("t2", "Alpha Corp", "ORG"));
    }

    @Test
    void entityIdDistinguishesTypes() {
        assertThat(GraphIds.entityId("t1", "Alpha", "ORG"))
            .isNotEqualTo(GraphIds.entityId("t1", "Alpha", "PRODUCT"));
    }
}
