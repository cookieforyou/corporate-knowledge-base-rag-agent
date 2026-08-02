package com.enterprise.kb.api.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 异步支持配置（2.12 热修）
 *
 * <p>SSE 流式端点返回 Flux 后，MVC 默认用 SimpleAsyncTaskExecutor 做异步处理
 * （启动告警「not suitable for production use under load」）。复用检索虚拟线程
 * 执行器（含请求上下文传播），消除告警并具备生产可用性。
 */
@Configuration
public class AsyncMvcConfig implements WebMvcConfigurer {

    private final AsyncTaskExecutor retrievalExecutor;

    public AsyncMvcConfig(@Qualifier("retrievalExecutor") AsyncTaskExecutor retrievalExecutor) {
        this.retrievalExecutor = retrievalExecutor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(retrievalExecutor);
    }
}
