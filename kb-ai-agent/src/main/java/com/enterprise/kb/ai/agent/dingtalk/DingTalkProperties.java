package com.enterprise.kb.ai.agent.dingtalk;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 钉钉群机器人配置族（Phase5簇⑥ 5.12，实施方案 D2-A/D3-A 定案）
 *
 * <p><b>缺省关纪律</b>：{@code enabled} 缺省 false——关闭态 Stream 客户端 Bean
 * 缺位（DingTalkStreamConfig 条件装配），三链与既有端点逐字节不变；开启态
 * 三要素（clientId/clientSecret/tenantId）fail-fast 启动校验。
 *
 * <p><b>身份定案（D2-A 服务账号单租户）</b>：{@code tenantId} 显式绑定唯一
 * 租户——群机器人无 JWT，租户纪律（检索链 fail-closed）由启动期拦截承载：
 * 开启而未配置即启动失败，绝不以裸租户形态进检索链。
 *
 * <p><b>密钥纪律</b>：clientId/clientSecret 经 env 占位注入（RAG_DINGTALK_CLIENT_ID/
 * SECRET），yml 零字面真值（infra/.env.example Secrets 模板同步）。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rag.dingtalk")
public class DingTalkProperties {

    /** 总开关（缺省关——用户侧 E2E 验证后启用，同族缺省关纪律） */
    private boolean enabled = false;

    /** 企业内部应用 AppKey（钉钉开发者后台） */
    private String clientId = "";

    /** 企业内部应用 AppSecret（env 注入，yml 零字面） */
    private String clientSecret = "";

    /** 服务账号绑定的唯一租户 ID（D2-A：开启态缺失启动失败） */
    private String tenantId = "";

    /**
     * 单次对话链超时（秒）：群聊回复无硬性时限，兜底防无限挂起；
     * 超时后台对话不中断（非打断式，坑位㊶ 同哲学）仅放弃等待回复超时话术。
     */
    private long chatTimeoutSeconds = 120;

    private final RateLimit ratelimit = new RateLimit();

    private final Audit audit = new Audit();

    @Getter
    @Setter
    public static class RateLimit {

        /** 限流开关（fail-open：Redis 故障放行——可用性管控非安全边界） */
        private boolean enabled = true;

        /** 单租户速率上限（群聊低频，较 MCP 只读通道更保守起步） */
        private long rate = 20;

        /** 速率窗口（秒） */
        private long intervalSeconds = 60;
    }

    @Getter
    @Setter
    public static class Audit {

        /** DB 轻行开关（kb_audit_log mode=dingtalk，默认关——先经结构化日志面观察，MCP B3 同款） */
        private boolean enabled = false;
    }
}
