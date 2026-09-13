package com.enterprise.kb.ai.agent.dingtalk;

import com.enterprise.kb.commons.constant.Constants;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DingTalkStreamConfig 装配语义测试（Phase5簇⑥ 5.12）：
 * 关闭态 Bean 缺位（零形态）、开启态三要素 fail-closed、就绪态装配。
 */
class DingTalkStreamConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(DingTalkStreamConfig.class, Config.class)
        .withBean(DingTalkChatService.class, () -> org.mockito.Mockito.mock(DingTalkChatService.class));

    @Test
    void disabledByDefaultNoStreamClient() {
        runner.run(ctx -> {
            // 缺省关：Stream 客户端 Bean 缺位——全系统逐字节零变化（缺省关纪律）
            assertThat(ctx).doesNotHaveBean(DingTalkStreamClient.class);
            // 执行器恒装配（ChatService 编译期依赖；虚拟线程零任务零资源）
            assertThat(ctx).hasBean(Constants.BeanNames.DINGTALK_EXECUTOR);
        });
    }

    @Test
    void enabledWithoutTenantIdFailsFast() {
        runner.withPropertyValues(
                "rag.dingtalk.enabled=true",
                "rag.dingtalk.client-id=app-key",
                "rag.dingtalk.client-secret=app-secret")
            .run(ctx -> {
                // D2-A fail-closed：tenant-id 缺失启动失败——绝不以裸租户形态进检索链
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).hasMessageContaining("rag.dingtalk.tenant-id");
            });
    }

    @Test
    void enabledWithoutCredentialFailsFast() {
        runner.withPropertyValues(
                "rag.dingtalk.enabled=true",
                "rag.dingtalk.tenant-id=tenant-a")
            .run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).hasMessageContaining("rag.dingtalk.client-id");
            });
    }

    @Test
    void enabledWithAllElementsAssembles() {
        // 直调工厂方法而非容器刷新：SmartLifecycle 在 refresh 期真建连（假凭证外网
        // 请求不可取）——三要素齐备时校验通过即装配就绪，连接行为归用户侧 E2E
        DingTalkProperties properties = new DingTalkProperties();
        properties.setEnabled(true);
        properties.setClientId("app-key");
        properties.setClientSecret("app-secret");
        properties.setTenantId("tenant-a");

        DingTalkStreamClient client = new DingTalkStreamConfig()
            .dingTalkStreamClient(properties, org.mockito.Mockito.mock(DingTalkChatService.class));

        assertThat(client).isNotNull();
        assertThat(client.isRunning()).isFalse(); // 连接在容器 lifecycle 期建立，未启动态
    }

    /** 测试装配面：显式注册被测配置 + 开启属性绑定（手工 @Bean 不经绑定后处理器，
     *  withPropertyValues 会静默不生效——EnableConfigurationProperties 注册绑定链） */
    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(DingTalkProperties.class)
    static class Config {
    }
}
