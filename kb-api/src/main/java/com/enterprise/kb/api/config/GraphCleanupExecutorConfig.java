package com.enterprise.kb.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;

/**
 * 图谱清理异步执行器（簇④ 批3 生命周期补强）—— 虚拟线程，I/O 密集（Neo4j 多事务往返）
 *
 * <p>文档删除的图引用清理（锚点删除 + 引用摘除 + 孤儿清扫）自删除同步链改异步旁路：
 * removeDocument 含全图孤儿扫描，经 bolt+s 多事务往返可达数十秒（E2E 实测 ~20s），
 * 同步等待拖长删除响应（前端盲等）。异步化后语义不变——尽力而为，图故障仅告警。
 *
 * <p>与 ETL / 归档执行器分离：图清理积压不得与文档解析、会话归档互相牵连
 * （sessionArchiveExecutor 同款隔离纪律）。
 */
@Configuration
public class GraphCleanupExecutorConfig {

    @Bean("graphCleanupExecutor")
    public AsyncTaskExecutor graphCleanupExecutor() {
        return new VirtualThreadTaskExecutor("graph-cleanup-");
    }
}
