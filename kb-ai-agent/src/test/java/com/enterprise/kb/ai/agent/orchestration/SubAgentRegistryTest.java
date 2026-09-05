package com.enterprise.kb.ai.agent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 子代理注册表测试（簇⑤ 5.3）——注册/查找/清单渲染 + Spec 构造校验
 */
class SubAgentRegistryTest {

    private static SubAgentSpec spec(String name) {
        return new SubAgentSpec(name, name + " 职责描述", name + " 系统指令",
            List.of(), null, 60);
    }

    @Test
    void findReturnsRegisteredSpec() {
        SubAgentRegistry registry = new SubAgentRegistry(List.of(spec("data-query"), spec("report-writer")));

        assertThat(registry.find("data-query")).isNotNull();
        assertThat(registry.find("data-query").description()).isEqualTo("data-query 职责描述");
        assertThat(registry.find("unknown")).isNull();
    }

    @Test
    void rosterRendersNameAndDescription() {
        SubAgentRegistry registry = new SubAgentRegistry(List.of(spec("data-query"), spec("report-writer")));

        String roster = registry.renderRoster();

        assertThat(roster).contains("- data-query — data-query 职责描述");
        assertThat(roster).contains("- report-writer — report-writer 职责描述");
    }

    @Test
    void renderNamesJoinsAll() {
        SubAgentRegistry registry = new SubAgentRegistry(List.of(spec("a"), spec("b"), spec("c")));

        assertThat(registry.renderNames()).isEqualTo("a, b, c");
    }

    @Test
    void blankNameRejected() {
        assertThatThrownBy(() -> spec(" "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankDescriptionRejected() {
        assertThatThrownBy(() -> new SubAgentSpec("name", " ", "sys", List.of(), null, 60))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("description");
    }

    @Test
    void nonPositiveTimeoutRejected() {
        assertThatThrownBy(() -> new SubAgentSpec("name", "职责", "sys", List.of(), null, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("timeoutSeconds");
    }

    @Test
    void nullToolObjectsNormalizedToEmpty() {
        SubAgentSpec spec = new SubAgentSpec("name", "职责", "sys", null, null, 60);

        assertThat(spec.toolObjects()).isEmpty();
    }
}
