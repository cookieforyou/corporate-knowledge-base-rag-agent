package com.enterprise.kb.ai.agent.dingtalk;

import com.enterprise.kb.commons.constant.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 钉钉群机器人装配（Phase5簇⑥ 5.12）
 *
 * <p><b>装配分界</b>：服务域组件（Properties/RateLimiter/AuditRecorder/ReplyClient/
 * ChatService）恒装配（无副作用，关闭态仅闲置——MCP 四件同款）；唯一有副作用
 * 的 Stream 客户端经 {@code rag.dingtalk.enabled} 条件装配——关闭态 Bean 缺位，
 * 三链与既有端点逐字节不变（缺省关纪律）。
 *
 * <p><b>fail-closed（D2-A）</b>：开启即校验三要素（clientId/clientSecret/
 * tenantId）——缺失抛异常启动失败。tenantId 空白形态绝不进检索链（身份纪律
 * 对齐 McpIdentityGuard 的 IDENTITY_INCOMPLETE 语义；Stream 客户端无请求
 * 线程可抛 4xx，拦截点前移至启动期）。
 *
 * <p><b>执行器</b>：虚拟线程（群聊长对话阻塞式调用廉价承载；同进程多执行器
 * 按名消歧——坑位㊺ @Qualifier 纪律，消费点显式 {@code Constants.BeanNames.DINGTALK_EXECUTOR}）。
 */
@Slf4j
@Configuration
public class DingTalkStreamConfig {

    /**
     * 钉钉对话执行器（恒装配：ChatService 编译期依赖，条件化会破恒装配服务域；
     * 虚拟线程执行器零任务零资源，关闭态闲置无害）。destroyMethod 推断自动
     * shutdown（Spring @Bean 缺省推断 public 无参 close/shutdown）。
     */
    @Bean(Constants.BeanNames.DINGTALK_EXECUTOR)
    public ExecutorService dingtalkExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** Stream 客户端（SmartLifecycle）：仅 rag.dingtalk.enabled=true 装配 */
    @Bean
    @ConditionalOnProperty(prefix = "rag.dingtalk", name = "enabled", havingValue = "true")
    public DingTalkStreamClient dingTalkStreamClient(DingTalkProperties properties,
                                                     DingTalkChatService chatService) {
        requireNonBlank(properties.getClientId(), "rag.dingtalk.client-id");
        requireNonBlank(properties.getClientSecret(), "rag.dingtalk.client-secret");
        // D2-A fail-closed：tenant-id 缺失启动失败——群机器人无 JWT，租户纪律由启动期拦截承载
        requireNonBlank(properties.getTenantId(), "rag.dingtalk.tenant-id");
        log.info("钉钉群机器人开启装配（租户绑定就绪）");
        return new DingTalkStreamClient(properties, chatService);
    }

    private static void requireNonBlank(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "钉钉群机器人已启用但 " + key + " 未配置——fail-closed 启动失败（防裸租户/裸凭证形态）");
        }
    }
}
