package com.enterprise.kb.ai.agent.a2a;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * A2A 协议端点配置族（Phase5簇⑥ 批2，N3 最小形态；spike 判负后 D1-A 自研协议层）
 *
 * <p><b>缺省关纪律</b>：{@code enabled} 缺省 false——关闭态协议 Controller 缺位
 * （端点 404 语义自然缺位），三链与既有端点逐字节不变；开启态 Card url
 * fail-fast 启动校验（v1.0 spec：supportedInterfaces.url required）。
 *
 * <p><b>治理面（MCP 四件同构）</b>：scope 治理 {@code rag.a2a.scope.required}
 * （默认空 = 仅租户纪律，Casdoor 无标准 scope claim 现状的治理抓手）；
 * 独立限流桶 {@code rag:ratelimit:a2a:{tenantId}} fail-open；入口轻审计
 * mode=a2a（默认关，结构化日志恒开）。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rag.a2a")
public class A2aProperties {

    /** 总开关（缺省关——用户侧 E2E 验证后启用，同族缺省关纪律） */
    private boolean enabled = false;

    private final Card card = new Card();

    private final Scope scope = new Scope();

    private final RateLimit ratelimit = new RateLimit();

    private final Audit audit = new Audit();

    /** Agent Card 发布内容（v1.0 spec §4.4.1 字段子集——最小形态裁剪） */
    @Getter
    @Setter
    public static class Card {

        /** Agent 名称（Card.name required） */
        private String name = "Enterprise KB RAG Agent";

        /** Agent 描述（Card.description required） */
        private String description = "企业知识库检索问答（混合检索 + 重排 + 带溯源回答）";

        /** Agent 版本（Card.version required） */
        private String version = "1.0.0";

        /** 服务外部可达地址（supportedInterfaces[0].url；开启态必配 fail-fast） */
        private String url = "";
    }

    /** scope 治理（McpIdentityGuard 同构抓手） */
    @Getter
    @Setter
    public static class Scope {

        /** 要求的 JWT scope 声明（默认空 = 仅租户纪律） */
        private String required = "";
    }

    @Getter
    @Setter
    public static class RateLimit {

        /** 限流开关（fail-open：Redis 故障放行——可用性管控非安全边界） */
        private boolean enabled = true;

        /** 单租户速率上限（机对机通道，与 MCP 只读通道同档起步） */
        private long rate = 20;

        /** 速率窗口（秒） */
        private long intervalSeconds = 60;
    }

    @Getter
    @Setter
    public static class Audit {

        /** DB 轻行开关（kb_audit_log mode=a2a，默认关——先经结构化日志面观察，MCP B3 同款） */
        private boolean enabled = false;
    }
}
