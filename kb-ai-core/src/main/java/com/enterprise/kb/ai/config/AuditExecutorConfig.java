package com.enterprise.kb.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;

/**
 * 审计落库执行器（3.12）：虚拟线程异步，审计旁路不占响应路径延迟
 * （与 retrievalExecutor / sessionArchiveExecutor 同款虚拟线程栈）。
 */
@Configuration
public class AuditExecutorConfig {

    @Bean
    public AsyncTaskExecutor auditExecutor() {
        return new VirtualThreadTaskExecutor("audit-");
    }
}
