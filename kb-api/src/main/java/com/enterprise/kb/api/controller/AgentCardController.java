package com.enterprise.kb.api.controller;

import com.enterprise.kb.ai.agent.a2a.A2aProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A2A Agent Card 发布端点（Phase5簇⑥ 批2，v1.0 spec §8）
 *
 * <p><b>发布内容（§8.5 样例子集）</b>：supportedInterfaces 单条
 * JSONRPC/1.0（required 字段，首条即 preferred）；能力位只声明同步
 * （streaming/pushNotifications 双 false——最小形态纪律，不声明即 MUST 拒
 * -32004）；securitySchemes v1.0 判别联合形态 bearer → httpAuthSecurityScheme
 * （scheme=Bearer + bearerFormat=JWT，对应 SecurityConfig JWT resource server）；
* skills 单条 kb_qa（知识问答）。
 *
 * <p><b>鉴权语义（方案 §3.3 定案）</b>：协议语义 Card 为公开发现，本项目单租户
 * 内部形态（Card 含内网端点信息）跟随 /mcp 先例 authenticated——标准 A2A Client
 * 支持凭据发现后带 token 拉取，E2E 覆盖该路径。
 *
 * <p><b>缓存（§8.6 SHOULD）</b>：Cache-Control max-age 1h + ETag（Card.version
 * 派生）——内容变化低频，条件请求免重传。
 *
 * <p><b>fail-fast</b>：开启态 url（supportedInterfaces[0].url，v1.0 required）
 * 缺失即启动失败——对齐钉钉三要素纪律。
 */
@Slf4j
@RestController
@ConditionalOnProperty(prefix = "rag.a2a", name = "enabled", havingValue = "true")
public class AgentCardController {

    /** 协议绑定值（v1.0 §4.4.6：核心绑定 JSONRPC / GRPC / HTTP+JSON） */
    private static final String PROTOCOL_BINDING_JSONRPC = "JSONRPC";
    private static final String PROTOCOL_VERSION = "1.0";
    private static final long CACHE_MAX_AGE_SECONDS = 3600L;

    private final A2aProperties properties;

    public AgentCardController(A2aProperties properties) {
        this.properties = properties;
        String url = properties.getCard().getUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                "rag.a2a.card.url 未配置——Agent Card supportedInterfaces.url 为 v1.0 required 字段（服务外部可达地址），开启态必须显式配置");
        }
        log.info("A2A Agent Card 发布就绪: url={}, version={}", url, properties.getCard().getVersion());
    }

    /** Agent Card 发现端点（§8.2 Well-Known URI 标准位） */
    @GetMapping("/.well-known/agent-card.json")
    public ResponseEntity<AgentCardView> agentCard() {
        AgentCardView card = new AgentCardView(
            properties.getCard().getName(),
            properties.getCard().getDescription(),
            List.of(new AgentInterfaceView(properties.getCard().getUrl(),
                PROTOCOL_BINDING_JSONRPC, PROTOCOL_VERSION)),
            properties.getCard().getVersion(),
            new AgentCapabilitiesView(false, false),
            Map.of("bearer", new SecuritySchemeView(
                new HttpAuthSecuritySchemeView("Bearer", "JWT"))),
            List.of(new SecurityRequirementView(Map.of("bearer", new ScopeSelectorView(List.of())))),
            List.of("text/plain"),
            List.of("text/plain"),
            List.of(new SkillView("kb_qa", "企业知识库问答",
                "企业知识库检索问答：混合检索（向量+BM25）+ 重排 + 带溯源（[ref-N] 锚定）回答，空证据拒答",
                List.of("knowledge-base", "rag", "question-answering"))));
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofSeconds(CACHE_MAX_AGE_SECONDS)))
            .eTag("\"" + properties.getCard().getVersion() + "\"")
            .body(card);
    }

    // ── Card 视图 DTO（v1.0 §4.4 字段子集）──

    /** AgentCard（§4.4.1）——provider/documentationUrl/iconUrl/signatures 最小形态不产出 */
    public record AgentCardView(String name, String description,
                                List<AgentInterfaceView> supportedInterfaces,
                                String version,
                                AgentCapabilitiesView capabilities,
                                Map<String, SecuritySchemeView> securitySchemes,
                                List<SecurityRequirementView> securityRequirements,
                                List<String> defaultInputModes,
                                List<String> defaultOutputModes,
                                List<SkillView> skills) {
    }

    /** AgentInterface（§4.4.6）——tenant 最小形态不产出（单租户不路由） */
    public record AgentInterfaceView(String url, String protocolBinding, String protocolVersion) {
    }

    /** AgentCapabilities（§4.4.3）——双 false：流式与推送不声明（能力位收窄） */
    public record AgentCapabilitiesView(boolean streaming, boolean pushNotifications) {
    }

    /** SecurityScheme（§4.5.1 判别联合）——httpAuthSecurityScheme 分支 */
    public record SecuritySchemeView(HttpAuthSecuritySchemeView httpAuthSecurityScheme) {
    }

    /** HTTPAuthSecurityScheme（§4.5.3）——scheme IANA 注册名 + bearerFormat 提示 */
    public record HttpAuthSecuritySchemeView(String scheme, String bearerFormat) {
    }

    /** SecurityRequirement（§8.5 样例形态）：schemes → 选择器（list 空 = 无 scope 要求） */
    public record SecurityRequirementView(Map<String, ScopeSelectorView> schemes) {
    }

    /** scope 选择器（§8.5 样例：{"schemes":{"google":{"list":[...]}}} 包装形态） */
    public record ScopeSelectorView(List<String> list) {
    }

    /** AgentSkill（§4.4.5）——examples/inputModes/outputModes 走 Card 缺省 */
    public record SkillView(String id, String name, String description, List<String> tags) {
    }
}
