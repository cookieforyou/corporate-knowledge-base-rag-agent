package com.enterprise.kb.ai.retriever;

import lombok.Getter;
import lombok.Setter;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求级检索上下文（设计文档 10.2.1）
 *
 * <p>承载跨检索组件的请求级状态：
 * <ul>
 *   <li>安全过滤：tenant_id 隔离 + 软删除过滤（Phase 3 追加 doc_id/dept_id ACL），
 *       向量路（2.7 VectorStoreDocumentRetriever）消费 {@link #getSecurityFilter()}，
 *       ES 路（2.6）直接读 tenantId 拼 term 过滤</li>
 *   <li>溯源 trace：双路检索命中记录暂存，供 2.11/2.12 SSE TRACE 事件与审计消费</li>
 * </ul>
 *
 * <p>填充方：RetrievalTraceAdvisor（2.11，Order 450）从 SecurityContext 提取 JWT claims 写入。
 * 非 Web 上下文（如 kb-eval 评估进程）中不存在本 Bean 实例，调用方经
 * {@code RequestContextHolder} 判空后降级（无租户过滤、无 trace 记录）。
 */
@Component
@RequestScope
public class RetrievalContext {

    @Getter @Setter
    private String tenantId;

    @Getter @Setter
    private String userId;

    private Filter.Expression securityFilter;

    @Getter
    private final List<TraceEntry> traceEntries = new ArrayList<>();

    /**
     * 向量库安全过滤表达式（懒构建）：tenant_id 等值 + 软删除过滤。
     * Phase 3 在此追加 allowed_doc_ids / allowed_dept_ids 维度（10.2.1）。
     */
    public Filter.Expression getSecurityFilter() {
        if (securityFilter == null && tenantId != null) {
            var b = new FilterExpressionBuilder();
            securityFilter = b.and(
                b.eq("tenant_id", tenantId),
                b.eq("is_deleted", false)
            ).build();
        }
        return securityFilter;
    }

    public void addTraceEntry(String source, List<Document> documents) {
        traceEntries.add(new TraceEntry(source, documents));
    }

    /** 单路检索的 trace 记录：来源标识（vector / bm25）+ 该路命中（含得分元数据） */
    public record TraceEntry(String source, List<Document> documents) {}
}
