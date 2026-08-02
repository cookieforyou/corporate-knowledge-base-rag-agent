package com.enterprise.kb.ai.retriever;

import lombok.Getter;
import lombok.Setter;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 请求级检索上下文（设计文档 10.2.1）—— **每请求纯实例，经 Advisor 参数传递**
 *
 * <p>传递链路（线程模型无关，同步/流式/虚拟线程一致）：
 * Controller 在请求线程创建实例并填充身份（JwtUtils）→ ChatClient advisor 参数
 * （{@link #CONTEXT_KEY}）→ RetrievalAugmentationAdvisor 复制进 Query.context
 * （源码核验：before() 以请求上下文构建 Query）→ 检索器/重排器经 {@link #from(Query)}
 * 读取 → Controller 流末直接读取同一实例推送 SSE TRACE。
 *
 * <p><b>2026-08-02 重构</b>：原为 @RequestScope + CGLIB 作用域代理。E2E 实证：
 * MVC 异步请求在请求线程返回后标记 ServletRequestAttributes 完结，作用域代理在
 * 整个流式生命周期内不可解析（ScopeNotActiveException），且所有消费点被守卫静默
 * 降级——租户过滤与 trace 在流式路径实际全部失效。改为参数传递后彻底绕开
 * ThreadLocal/请求作用域/线程模型。
 *
 * <p>承载状态：
 * <ul>
 *   <li>安全过滤：tenant_id 隔离 + 软删除过滤（Phase 3 追加 doc_id/dept_id ACL），
 *       向量路消费 {@link #getSecurityFilter()}，ES 路直接读 tenantId 拼 term 过滤</li>
 *   <li>溯源 trace：双路命中 + 重排后最终序列，供 SSE TRACE 事件与审计消费。
 *       双检索路径并行写入，容器取 CopyOnWriteArrayList 保证线程安全</li>
 * </ul>
 *
 * <p>非 Web 入口（kb-eval 评估）不创建实例，{@link #from(Query)} 返回 null，
 * 各组件降级：无租户过滤、无 trace（评估期可接受）。
 */
public class RetrievalContext {

    /** Advisor 参数键：Controller 经 spec.param 传入，随 Query.context 流至检索组件 */
    public static final String CONTEXT_KEY = "kb.retrieval_context";

    /** 从 Query 上下文提取检索上下文；无则 null（非 Web 入口降级路径） */
    @Nullable
    public static RetrievalContext from(Query query) {
        Object value = query.context().get(CONTEXT_KEY);
        return value instanceof RetrievalContext ctx ? ctx : null;
    }

    @Getter @Setter
    private String tenantId;

    @Getter @Setter
    private String userId;

    private volatile Filter.Expression securityFilter;

    private final List<TraceEntry> traceEntries = new CopyOnWriteArrayList<>();

    /**
     * 向量库安全过滤表达式（懒构建，双检锁）：tenant_id 等值 + 软删除过滤。
     * Phase 3 在此追加 allowed_doc_ids / allowed_dept_ids 维度（10.2.1）。
     */
    @Nullable
    public Filter.Expression getSecurityFilter() {
        if (securityFilter == null && tenantId != null) {
            synchronized (this) {
                if (securityFilter == null && tenantId != null) {
                    var b = new FilterExpressionBuilder();
                    securityFilter = b.and(
                        b.eq("tenant_id", tenantId),
                        b.eq("is_deleted", false)
                    ).build();
                }
            }
        }
        return securityFilter;
    }

    public void addTraceEntry(String source, List<Document> documents) {
        traceEntries.add(new TraceEntry(source, documents));
    }

    /** trace 列表快照（SSE TRACE 事件 / 审计数据源；source=final 为重排后最终注入序列，[ref-N] 与其下标对齐） */
    public List<TraceEntry> getTraceSummary() {
        return List.copyOf(traceEntries);
    }

    /** 全 trace 最高融合分（调试/可观测用；无 fusion_score 回退 Document.score，无命中返回 0） */
    public double getTopFusionScore() {
        return traceEntries.stream()
            .flatMap(e -> e.documents().stream())
            .mapToDouble(d -> {
                Object fusion = d.getMetadata().get("fusion_score");
                if (fusion instanceof Number n) return n.doubleValue();
                return d.getScore() != null ? d.getScore() : 0.0;
            })
            .max().orElse(0.0);
    }

    /** 单路检索的 trace 记录：来源标识（vector / bm25 / final）+ 该路命中（含得分元数据） */
    public record TraceEntry(String source, List<Document> documents) {}
}
