package com.enterprise.kb.api.controller;

import com.enterprise.kb.ai.agent.a2a.A2aProperties;
import com.enterprise.kb.api.controller.AgentCardController.AgentCardView;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AgentCardController 测试（Phase5簇⑥ 批2，v1.0 §4.4/§8.5 语义）：
 * url fail-fast、Card 字段形态（supportedInterfaces/判别联合 securitySchemes/
 * 能力位双 false/skills）、缓存头。
 */
class AgentCardControllerTest {

    private static A2aProperties propertiesWithUrl(String url) {
        A2aProperties properties = new A2aProperties();
        properties.getCard().setUrl(url);
        return properties;
    }

    @Test
    void missingUrlFailsFast() {
        // 开启态 fail-closed：supportedInterfaces.url 为 v1.0 required 字段
        assertThatThrownBy(() -> new AgentCardController(propertiesWithUrl("")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("rag.a2a.card.url");
    }

    @Test
    void cardFieldsFollowV10Spec() {
        AgentCardController controller = new AgentCardController(propertiesWithUrl("https://kb.example.com/a2a"));

        ResponseEntity<AgentCardView> entity = controller.agentCard();
        AgentCardView card = entity.getBody();

        assertThat(card).isNotNull();
        assertThat(card.name()).isEqualTo("Enterprise KB RAG Agent");
        assertThat(card.description()).isNotBlank();
        assertThat(card.version()).isEqualTo("1.0.0");
        // supportedInterfaces 单条 JSONRPC/1.0（required 字段，首条即 preferred）
        assertThat(card.supportedInterfaces()).hasSize(1);
        assertThat(card.supportedInterfaces().get(0).url()).isEqualTo("https://kb.example.com/a2a");
        assertThat(card.supportedInterfaces().get(0).protocolBinding()).isEqualTo("JSONRPC");
        assertThat(card.supportedInterfaces().get(0).protocolVersion()).isEqualTo("1.0");
        // 能力位只声明同步：流式/推送不声明（最小形态纪律——不声明即 MUST 拒 -32004）
        assertThat(card.capabilities().streaming()).isFalse();
        assertThat(card.capabilities().pushNotifications()).isFalse();
        // securitySchemes v1.0 判别联合形态（bearer → httpAuthSecurityScheme）
        assertThat(card.securitySchemes()).containsKey("bearer");
        assertThat(card.securitySchemes().get("bearer").httpAuthSecurityScheme().scheme()).isEqualTo("Bearer");
        assertThat(card.securitySchemes().get("bearer").httpAuthSecurityScheme().bearerFormat()).isEqualTo("JWT");
        // securityRequirements §8.5 样例包装形态（list 空 = 无 scope 要求）
        assertThat(card.securityRequirements()).hasSize(1);
        assertThat(card.securityRequirements().get(0).schemes()).containsKey("bearer");
        assertThat(card.securityRequirements().get(0).schemes().get("bearer").list()).isEmpty();
        assertThat(card.defaultInputModes()).containsExactly("text/plain");
        assertThat(card.defaultOutputModes()).containsExactly("text/plain");
        assertThat(card.skills()).hasSize(1);
        assertThat(card.skills().get(0).id()).isEqualTo("kb_qa");
        assertThat(card.skills().get(0).tags()).isNotEmpty();
    }

    @Test
    void cacheHeadersPresent() {
        AgentCardController controller = new AgentCardController(propertiesWithUrl("https://kb.example.com/a2a"));

        ResponseEntity<AgentCardView> entity = controller.agentCard();

        // §8.6 SHOULD：max-age 缓存 + ETag（Card.version 派生）
        assertThat(entity.getHeaders().getCacheControl()).contains("max-age=3600");
        assertThat(entity.getHeaders().getETag()).isNotBlank();
    }
}
