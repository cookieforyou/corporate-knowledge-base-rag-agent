package com.enterprise.kb.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 会话归档异步执行器（3.1）—— 虚拟线程，I/O 密集（PG 写入）
 *
 * <p>与 ETL 执行器（etlExecutor）分离：归档失败/积压不得与文档解析互相牵连。
 */
@Configuration
public class SessionArchiveConfig {

    @Bean("sessionArchiveExecutor")
    public Executor sessionArchiveExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
