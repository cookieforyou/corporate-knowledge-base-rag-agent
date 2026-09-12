package com.enterprise.kb.api.observability;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * trace 上下文传播配置（Phase 4 簇①）
 *
 * <p>更名注记（2026-09-12）：原 LangfuseObservabilityConfig——簇① 时代观测后端唯
 * 一为 Langfuse 云故随物命名；本类职责（Reactor 上下文传播接线）与后端无关，Jaeger
 * 本栈形态实证同服务，随 env 键族 LANGFUSE_* → TRACING_* 归一化同批正名。
 *
 * <p>Reactor 自动上下文传播——簇① E2E 实证缺陷修复：ChatClient/检索/embedding 各
 * observation 在 SSE 流式链路上散为孤立 trace（不挂 HTTP 请求父 trace）。开
 * {@link Hooks#enableAutomaticContextPropagation()} 后，订阅点的 OTel/Tracing
 * ThreadLocal 经 context-propagation 桥捕获入 Reactor Context、算子内恢复，span
 * 父子关系复原。须早于首个 Flux 装配，故 Bean 实例化即开。
 *
 * <p>内容捕获（gen_ai.prompt/gen_ai.completion 落 span attribute）经 kb-ai-core 的
 * {@code ContentCapturingChatModelObservationConvention} + SmartRoutingConfig 条件装配，
 * 与本配置分域（内容属模型观测约定，传播属宿主运行时接线）。
 */
@Slf4j
@Configuration
public class TraceContextPropagationConfig {

    @Bean
    ReactorContextPropagationEnabler reactorContextPropagationEnabler() {
        return new ReactorContextPropagationEnabler();
    }

    /**
     * 构造即启用 Reactor 自动上下文传播（trace 上下文跨流式算子传播）。
     *
     * <p>实证坑（簇①）：{@code ContextRegistry} 全局实例**不自动装载** ServiceLoader
     * 注册的 ThreadLocalAccessor（含 micrometer-observation 的 ObservationThreadLocal
     * Accessor）——须显式 {@link ContextRegistry#loadThreadLocalAccessors()}，否则
     * Reactor contextCapture 为空操作，span 父子关系静默断裂（观测表现为 trace 碎片化）。
     */
    static class ReactorContextPropagationEnabler {
        ReactorContextPropagationEnabler() {
            ContextRegistry registry = ContextRegistry.getInstance();
            registry.loadThreadLocalAccessors();
            Hooks.enableAutomaticContextPropagation();
            log.info("Reactor 自动上下文传播已启用，已装载 ThreadLocalAccessor: {}",
                registry.getThreadLocalAccessors().stream()
                    .map(ThreadLocalAccessor::key).toList());
        }
    }
}
