package com.enterprise.kb.ai.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 编排链装配契约测试（簇⑤ E2E 热修一）——坑位㊺：容器内 ExecutorService Bean
 * 不唯一（另有 kb-ai-core 的 hybridRetrievalExecutor），按类型注入消费点必须
 * 显式 @Qualifier 限定，否则开关开启态启动歧义失败（IDEA 编译无 -parameters
 * 时按名消歧亦失效——2026-09-05 用户侧首次开启开关 IDEA 启动实证）。
 */
class OrchestratorChatClientConfigTest {

    /** taskTool 装配的 executor 参数限定符钉死（防回退：限定符删除即启动炸） */
    @Test
    void taskToolExecutorInjectionPointPinnedByQualifier() {
        var method = Arrays.stream(OrchestratorChatClientConfig.class.getDeclaredMethods())
            .filter(m -> "taskTool".equals(m.getName()))
            .findFirst().orElseThrow();
        var param = Arrays.stream(method.getParameters())
            .filter(p -> p.getType() == ExecutorService.class)
            .findFirst().orElseThrow();
        var qualifier = param.getAnnotation(Qualifier.class);
        assertNotNull(qualifier, "taskTool 的 executor 参数必须显式 @Qualifier（坑位㊺）");
        assertEquals("orchestratorSubAgentExecutor", qualifier.value());
    }
}
