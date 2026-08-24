package com.enterprise.kb.ai.metrics;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.commons.guardrail.GuardrailFamily;
import com.enterprise.kb.commons.guardrail.OutputFamily;
import com.enterprise.kb.commons.security.pii.PiiType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 业务指标统一注册中心（设计文档 13.3，任务 3.13）
 *
 * <p>双层可观测分工（13 章定案）：Langfuse 管 LLM trace 树 / prompt / 评估；
 * 本组件 + Prometheus + Grafana 管 QPS、延迟分位、错误率与**业务指标**。
 * 全项目业务指标在此集中注册（3.7/3.8 的 token 计数随之收编），避免散点
 * MeterRegistry 注入造成命名漂移。
 *
 * <p><b>指标清单与挂点</b>：
 * <ul>
 *   <li>{@code rag.feedback.like / rag.feedback.dislike}——反馈计数，
 *       3.17 反馈 API 落地时经 {@link #recordFeedback(boolean)} 接线（先注册待接）</li>
 *   <li>{@code rag.retrieval.total / rag.retrieval.hit}——检索执行/命中计数，
 *       AuditTraceAdvisor 按 final trace 是否非空计命中（内层被拒未达检索不计入
 *       分母）；命中率 = Prometheus 侧 hit/total 相除</li>
 *   <li>{@code rag.retrieval.latency}——混合检索耗时 Timer（p50/p95/p99），
 *       HybridDocumentRetriever 记录真实耗时</li>
 *   <li>{@code rag.tool.call.total / success / pending}——工具调用计数，
 *       AuditTraceAdvisor 按 ToolCall.status 分桶（EXECUTED 计成功，
 *       PENDING_APPROVAL 计挂起待审）</li>
 *   <li>{@code rag.token.total / rag.token.budget.rejected}——Token 消耗与预算
 *       拒绝（原 TokenBudgetAdvisor 分散注册，收编至此统一管理）</li>
 *   <li>{@code rag.guardrail.injection.blocked / pii.masked / output.replaced /
 *       rate.limited / token.budget}——护栏命中计数（簇⑤ B2，S3），按事件类型
 *       分列注册（与 tool.call 分桶同形态）；注入/限流/预算拒绝同时经
 *       AuditTraceAdvisor 落 kb_audit_log REJECTED 行，指标供 Prometheus 告警。
 *       安全簇① T5 扩充：{@code output.replaced.{business_confidential /
 *       compliance_sensitive / competitor_comparison}} 三分类子项（switch 收口
 *       独立 Counter，零标签纪律）+ {@code output.canary} 系统提示金丝雀回显拦截。
 *       安全簇① T7 扩充：{@code rag.guardrail.flagged} FLAG 观察档命中计数——
 *       低基数标签 side=input/output + family 中性枚举（注入侧七分法 ∪ 输出侧
 *       三分类，各含 UNCLASSIFIED 兜底），side×family 全组合构造期预注册
 *       （有界枚举，无租户/用户维度，Prometheus 侧 sum/group by 聚合）。
 *       安全簇⑤ E1 扩充：{@code rag.guardrail.l2.triggered / blocked / suspect /
 *       error} L2 语义判定四态分列（SemanticInjectionAdvisor 触发/拦截/观察/故障，
 *       触发率 = triggered/request.total）。安全簇⑥ F1 扩充：
 *       {@code rag.guardrail.reload.succeeded / failed} 词表热重载成败二态
 *       （协调器触发，fail-keep 保旧快照语义）</li>
 *   <li>{@code rag.document.reindex.started / succeeded / failed}——文档增量重入库
 *       计数（簇⑥ C1）：started 于 reparse/replace 占用成功计，succeeded/failed
 *       经 ETL 进度回调 COMPLETED/FAILED 终态计（异步管线的观测点在回调层）</li>
 *   <li>{@code rag.request.total / rejected / error}——双链问答请求结果计数
 *       （Phase 4 簇①）：AuditTraceAdvisor 遍历双链全量请求旁路计数；
 *       rejected = BusinessException（护栏/配额拒绝，审计 REJECTED），
 *       error = 其他异常（供应商/系统，审计 ERROR）；为告警规则提供
 *       拒绝率/错误率分母（4.2）</li>
 *   <li>{@code rag.rerank.total / rag.rerank.fallback}——rerank 执行/降级计数
 *       （Phase 4 簇①）：RerankDocumentPostProcessor 运行时调用计 total，
 *       解析失败/结构异常/调用失败降级 fusion_score 截断计 fallback；
 *       endpoint 未配置的静态降级不计入（配置态非运行态）</li>
 *   <li>{@code rag.ttft}——流式首 Token 延迟 Timer（p50/p95/p99，Phase 4 簇② 4.3）：
 *       AgentController 流式路径自请求进入至首个非空 token 送达的端到端时延，
 *       双链共记（无 mode 标签，延续零标签纪律）；同步路径无首 token 语义不记</li>
 *   <li>{@code rag.chunk.edit / soft.delete / restore}——Chunk 运维操作计数
 *       （Phase 4 簇③ 4.4）：kb-admin ChunkOpsService 成功路径计；操作类型经
 *       recordChunkOps(String) 收口为独立 Counter（不加 operation 标签）</li>
 *   <li>{@code rag.badcase.annotate / rag.badcase.reingest}——Bad Case 运营闭环
 *       计数（Phase 4 簇④ 4.7）：根因标注 / Golden Set 回灌成功路径计，
 *       recordBadCaseOps(String) 收口（同款零标签纪律）</li>
 *   <li>{@code rag.routing.circuit.opened / circuit.half-opened / fallback.invoked}
 *       ——双供应商 SLA 计数族（Phase 4 簇⑥ 批4）：熔断 OPEN 转入（含 HALF_OPEN
 *       试探失败重开）/ 熔断窗口结束后主模型试探 / 备用模型接管请求，接线点
 *       SmartRoutingChatModel 熔断态变更与路由分支；主模型可用率与切换时序的
 *       指标底座（kb-rag-supplier-sla 面板 + KbPrimaryModelDegraded 告警消费）</li>
 *   <li>{@code rag.retrieval.cache.hit / cache.miss / cache.invalidated}
 *       ——语义缓存计数族（Phase 5 簇③ 5.6，13.3 预留位启用）：KNN 查找达阈命中
 *       / 未命中（含索引空与相似度不足）/ 按文档失效删除条数，接线点
 *       SemanticCacheService；命中率 = hit/(hit+miss)，对照 08 章簇③验收
 *       （>30% 真实流量）</li>
 * </ul>
 *
 * <p><b>标签纪律</b>：全部指标不带租户标签（防指标基数膨胀，3.8 定案延续）；
 * 租户级观测走 Redis 账本键与 kb_audit_log。唯一标签例外 {@code rag.guardrail.flagged}
 * （安全簇① T7）：side/family 均为有界中性枚举（任务分解定案的低基数标签形态）。
 *
 * <p>设计稿 13.3 其余指标暂不注册：{@code rag.llm.*}（模型层调用计数随
 * Phase 4 可观测增强）。{@code rag.retrieval.cache.hit} 已于 Phase 5 簇③ 5.6
 * 启用（语义缓存计数族，见上）。
 */
@Component
public class AiBusinessMetrics {

    /** FLAG 观察档命中侧标识（安全簇① T7）：输入护栏 / 输出护栏 */
    public static final String SIDE_INPUT = "input";
    public static final String SIDE_OUTPUT = "output";

    /** 族系兜底值（未标注/未知族系统一归口，防 tag 取值漂移） */
    private static final String FAMILY_UNCLASSIFIED = "UNCLASSIFIED";

    private final Counter feedbackLike;
    private final Counter feedbackDislike;
    private final Counter retrievalTotal;
    private final Counter retrievalHit;
    private final Timer retrievalLatency;
    private final Counter toolCallTotal;
    private final Counter toolCallSuccess;
    private final Counter toolCallPending;
    private final Counter tokenTotal;
    private final Counter tokenBudgetRejected;
    private final Counter routingChitchat;
    private final Counter routingKnowledge;
    private final Counter guardrailInjectionBlocked;
    private final Counter guardrailPiiMasked;
    // PII 掩码类型子项（安全簇③ C1/C2）：零标签纪律下沿用 output.replaced 子项
    // 形态——经类型枚举 switch 收口为独立 Counter
    private final Counter guardrailPiiMaskedPhone;
    private final Counter guardrailPiiMaskedIdCard;
    private final Counter guardrailPiiMaskedEmail;
    private final Counter guardrailPiiMaskedBankCard;
    private final Counter guardrailPiiMaskedLandline;
    private final Counter guardrailPiiMaskedLicensePlate;
    private final Counter guardrailPiiMaskedIpv4;
    private final Counter guardrailOutputReplaced;
    private final Counter guardrailOutputReplacedBusinessConfidential;
    private final Counter guardrailOutputReplacedComplianceSensitive;
    private final Counter guardrailOutputReplacedCompetitorComparison;
    private final Counter guardrailOutputCanary;
    /** 输出 PII 回显观察计数（安全簇③ / 簇① T5 钩子闭环）：FLAG 观察起步只计数不替换 */
    private final Counter guardrailOutputPiiEcho;
    /** 间接注入扫描命中条数（安全簇④ D1）：召回证据命中注入词表检测视图，按条计 */
    private final Counter guardrailIndirectFlagged;
    /** 间接注入扫描 exclude 策略剔除条数（安全簇④ D1）：命中证据被剔出 grounding */
    private final Counter guardrailIndirectExcluded;
    /** 入库打标降权条数（安全簇④ D2）：injection_hit chunk 融合分衰减生效计数 */
    private final Counter retrievalInjectionHitDemoted;
    /** FLAG 观察档计数（安全簇① T7）：键 side:family，side×family 全组合预注册 */
    private final Map<String, Counter> guardrailFlagged;
    private final Counter guardrailRateLimited;
    private final Counter guardrailTokenBudget;
    private final Counter documentReindexStarted;
    private final Counter documentReindexSucceeded;
    private final Counter documentReindexFailed;
    private final Counter requestTotal;
    private final Counter requestRejected;
    private final Counter requestError;
    private final Counter rerankTotal;
    private final Counter rerankFallback;
    private final Timer ttft;
    private final Counter chunkEdit;
    private final Counter chunkSoftDelete;
    private final Counter chunkRestore;
    private final Counter badCaseAnnotate;
    private final Counter badCaseReingest;
    private final Counter mcpSearch;
    private final Counter mcpGetDocument;
    private final Counter mcpAsk;
    /** MCP 只读工具限流拒绝计数（安全簇② B3）——独立桶，与对话链限流分账 */
    private final Counter guardrailMcpRateLimited;
    /** L2 语义判定触发计数（安全簇⑤ E1）：可疑触发进入二判的请求数（触发率分子，分母 rag.request.total） */
    private final Counter guardrailL2Triggered;
    /** L2 语义判定拦截计数（安全簇⑤ E1）：BLOCK 裁决 → PROMPT_INJECTION 同语义拒答 */
    private final Counter guardrailL2Blocked;
    /** L2 语义判定观察计数（安全簇⑤ E1）：SUSPECT 裁决 FLAG 放行（另计 rag.guardrail.flagged 族系子项） */
    private final Counter guardrailL2Suspect;
    /** L2 语义判定故障计数（安全簇⑤ E1）：超时/失败/解析错误 fail-open 回落 L1 结论 */
    private final Counter guardrailL2Error;
    /** 词表热重载成功计数（安全簇⑥ F1）：信号/轮询触发，双侧快照原子替换 + 监听器推送 */
    private final Counter guardrailReloadSucceeded;
    /** 词表热重载失败计数（安全簇⑥ F1）：装载失败 fail-keep 保旧快照（防线不因运营故障降级） */
    private final Counter guardrailReloadFailed;
    /** 词表运营 CRUD 新建计数（v2.53 词表 DB 单轨）：CRUD API 写路径落账 */
    private final Counter guardrailRuleCreated;
    /** 词表运营 CRUD 更新计数（v2.53 词表 DB 单轨） */
    private final Counter guardrailRuleUpdated;
    /** 词表运营 CRUD 删除计数（v2.53 词表 DB 单轨） */
    private final Counter guardrailRuleDeleted;
    /** 熔断 OPEN 转入计数（Phase 4 簇⑥ 批4）：首次达阈 OPEN + HALF_OPEN 试探失败重开均计 */
    private final Counter routingCircuitOpened;
    /** HALF_OPEN 试探计数（Phase 4 簇⑥ 批4）：熔断窗口结束后首个请求试探主模型 */
    private final Counter routingCircuitHalfOpened;
    /** 备用模型接管计数（Phase 4 簇⑥ 批4）：OPEN 直发 + 失败即切，双路径合计 */
    private final Counter routingFallbackInvoked;
    /** 语义缓存命中计数（Phase 5 簇③ 5.6，13.3 预留位启用）：KNN top-1 相似度达阈 */
    private final Counter cacheHit;
    /** 语义缓存未命中计数（Phase 5 簇③ 5.6）：索引空 / 相似度不足 / 运行期降级均计 */
    private final Counter cacheMiss;
    /** 语义缓存按文档失效删除条数（Phase 5 簇③ 5.6）：知识库变更事件驱动 */
    private final Counter cacheInvalidated;

    public AiBusinessMetrics(MeterRegistry registry) {
        this.feedbackLike = Counter.builder("rag.feedback.like")
            .description("用户点赞反馈数（3.17 反馈 API 接线点）").register(registry);
        this.feedbackDislike = Counter.builder("rag.feedback.dislike")
            .description("用户点踩反馈数（3.17 反馈 API 接线点）").register(registry);
        this.retrievalTotal = Counter.builder("rag.retrieval.total")
            .description("混合检索执行次数").register(registry);
        this.retrievalHit = Counter.builder("rag.retrieval.hit")
            .description("检索命中次数（final 序列非空）").register(registry);
        // v2.11 草图承诺 p50/p95/p99，实现期遗漏——簇② 4.3 面板消费分位时补齐
        this.retrievalLatency = Timer.builder("rag.retrieval.latency")
            .description("混合检索耗时")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
        this.toolCallTotal = Counter.builder("rag.tool.call.total")
            .description("工具调用次数").register(registry);
        this.toolCallSuccess = Counter.builder("rag.tool.call.success")
            .description("工具调用成功次数（EXECUTED）").register(registry);
        this.toolCallPending = Counter.builder("rag.tool.call.pending")
            .description("工具调用 HITL 挂起次数（PENDING_APPROVAL）").register(registry);
        this.tokenTotal = Counter.builder("rag.token.total")
            .description("AI Token 总消耗").register(registry);
        this.tokenBudgetRejected = Counter.builder("rag.token.budget.rejected")
            .description("Token 预算拒绝次数").register(registry);
        this.routingChitchat = Counter.builder("rag.routing.chitchat")
            .description("意图分类为闲聊/元问题，旁路检索直答（5.4 收窄版）").register(registry);
        this.routingKnowledge = Counter.builder("rag.routing.knowledge")
            .description("意图分类为知识问答，走完整检索链路（5.4 收窄版）").register(registry);
        this.guardrailInjectionBlocked = Counter.builder("rag.guardrail.injection.blocked")
            .description("Prompt 注入拦截次数（InputSanitizeAdvisor，簇⑤ B2 S3）").register(registry);
        this.guardrailPiiMasked = Counter.builder("rag.guardrail.pii.masked")
            .description("PII 掩码触发次数（InputSanitizeAdvisor，簇⑤ B2 S3）").register(registry);
        // PII 掩码类型子项（安全簇③ C1/C2）：七类确定性识别器各一子项，命名
        // rag.guardrail.pii.masked.{type}（对齐 output.replaced.{分类} 子项形态）
        this.guardrailPiiMaskedPhone = Counter.builder("rag.guardrail.pii.masked.phone")
            .description("PII 掩码类型子项——手机号（安全簇③ C1/C2）").register(registry);
        this.guardrailPiiMaskedIdCard = Counter.builder("rag.guardrail.pii.masked.id_card")
            .description("PII 掩码类型子项——身份证号（安全簇③ C1/C2）").register(registry);
        this.guardrailPiiMaskedEmail = Counter.builder("rag.guardrail.pii.masked.email")
            .description("PII 掩码类型子项——邮箱（安全簇③ C1/C2）").register(registry);
        this.guardrailPiiMaskedBankCard = Counter.builder("rag.guardrail.pii.masked.bank_card")
            .description("PII 掩码类型子项——银行卡号 Luhn（安全簇③ C1/C2）").register(registry);
        this.guardrailPiiMaskedLandline = Counter.builder("rag.guardrail.pii.masked.landline")
            .description("PII 掩码类型子项——座机号（安全簇③ C1/C2）").register(registry);
        this.guardrailPiiMaskedLicensePlate = Counter.builder("rag.guardrail.pii.masked.license_plate")
            .description("PII 掩码类型子项——车牌号（安全簇③ C1/C2）").register(registry);
        this.guardrailPiiMaskedIpv4 = Counter.builder("rag.guardrail.pii.masked.ipv4")
            .description("PII 掩码类型子项——IPv4 地址（安全簇③ C1/C2）").register(registry);
        this.guardrailOutputReplaced = Counter.builder("rag.guardrail.output.replaced")
            .description("输出敏感词表整段替换次数（OutputGuardrailAdvisor，簇⑤ B2 S3）").register(registry);
        // 输出面分类化子项（安全簇① T5）：按 OutputFamily 三分类分列——零标签纪律下
        // 经 recordOutputReplaced(family) switch 收口为独立 Counter（不加 family 标签）
        this.guardrailOutputReplacedBusinessConfidential =
            Counter.builder("rag.guardrail.output.replaced.business_confidential")
                .description("输出替换次数——业务保密分类（安全簇① T5）").register(registry);
        this.guardrailOutputReplacedComplianceSensitive =
            Counter.builder("rag.guardrail.output.replaced.compliance_sensitive")
                .description("输出替换次数——合规敏感分类（安全簇① T5）").register(registry);
        this.guardrailOutputReplacedCompetitorComparison =
            Counter.builder("rag.guardrail.output.replaced.competitor_comparison")
                .description("输出替换次数——竞品对比分类（安全簇① T5）").register(registry);
        this.guardrailOutputCanary = Counter.builder("rag.guardrail.output.canary")
            .description("系统提示金丝雀回显拦截次数——确证提示泄露（安全簇① T5）").register(registry);
        this.guardrailOutputPiiEcho = Counter.builder("rag.guardrail.output.pii.echo")
            .description("输出 PII 回显观察次数——回答检出未掩码强形态 PII，FLAG 观察起步只计数不替换（安全簇③ / 簇① T5 钩子）").register(registry);
        this.guardrailIndirectFlagged = Counter.builder("rag.guardrail.indirect.flagged")
            .description("间接注入扫描命中条数——召回证据命中注入词表检测视图，按条计（安全簇④ D1）").register(registry);
        this.guardrailIndirectExcluded = Counter.builder("rag.guardrail.indirect.excluded")
            .description("间接注入扫描 exclude 策略剔除条数——命中证据被剔出 grounding（安全簇④ D1）").register(registry);
        this.retrievalInjectionHitDemoted = Counter.builder("rag.retrieval.injection-hit.demoted")
            .description("入库打标降权条数——injection_hit chunk 融合分衰减生效（安全簇④ D2，默认关）").register(registry);
        // FLAG 观察档计数（安全簇① T7）：side×family 全组合预注册——side 两值、
        // family 取两套中性枚举（注入侧七分法 ∪ 输出侧三分类，各含 UNCLASSIFIED），
        // 序列数有界（低基数标签，任务分解定案形态），Prometheus 侧 sum/group by 聚合
        Map<String, Counter> flagged = new LinkedHashMap<>();
        for (GuardrailFamily family : GuardrailFamily.values()) {
            flagged.put(flagKey(SIDE_INPUT, family.name()),
                Counter.builder("rag.guardrail.flagged")
                    .description("护栏 FLAG 观察档命中次数——只计数+审计标记不拒绝（安全簇① T7）")
                    .tags("side", SIDE_INPUT, "family", family.name())
                    .register(registry));
        }
        for (OutputFamily family : OutputFamily.values()) {
            flagged.put(flagKey(SIDE_OUTPUT, family.name()),
                Counter.builder("rag.guardrail.flagged")
                    .description("护栏 FLAG 观察档命中次数——只计数+审计标记不拒绝（安全簇① T7）")
                    .tags("side", SIDE_OUTPUT, "family", family.name())
                    .register(registry));
        }
        flagged.put(flagKey(SIDE_OUTPUT, FAMILY_UNCLASSIFIED),
            Counter.builder("rag.guardrail.flagged")
                .description("护栏 FLAG 观察档命中次数——只计数+审计标记不拒绝（安全簇① T7）")
                .tags("side", SIDE_OUTPUT, "family", FAMILY_UNCLASSIFIED)
                .register(registry));
        this.guardrailFlagged = Map.copyOf(flagged);
        this.guardrailRateLimited = Counter.builder("rag.guardrail.rate.limited")
            .description("租户限流拒绝次数（RateLimitAdvisor，簇⑤ B2 S3）").register(registry);
        this.guardrailTokenBudget = Counter.builder("rag.guardrail.token.budget")
            .description("Token 预算拒绝次数——安全域视图（成本域同事件见 rag.token.budget.rejected，簇⑤ B2 S3）").register(registry);
        this.documentReindexStarted = Counter.builder("rag.document.reindex.started")
            .description("文档增量重入库发起次数（reparse/replace 占用成功，簇⑥ C1）").register(registry);
        this.documentReindexSucceeded = Counter.builder("rag.document.reindex.succeeded")
            .description("文档增量重入库成功次数（ETL 进度回调 COMPLETED，簇⑥ C1）").register(registry);
        this.documentReindexFailed = Counter.builder("rag.document.reindex.failed")
            .description("文档增量重入库失败次数（ETL 进度回调 FAILED，簇⑥ C1）").register(registry);
        this.requestTotal = Counter.builder("rag.request.total")
            .description("双链问答请求总数（审计旁路计数，Phase 4 簇①）").register(registry);
        this.requestRejected = Counter.builder("rag.request.rejected")
            .description("护栏/配额拒绝请求数（BusinessException → 审计 REJECTED，Phase 4 簇①）").register(registry);
        this.requestError = Counter.builder("rag.request.error")
            .description("供应商/系统错误请求数（审计 ERROR，Phase 4 簇①）").register(registry);
        this.rerankTotal = Counter.builder("rag.rerank.total")
            .description("rerank 运行时执行次数（Phase 4 簇①）").register(registry);
        this.rerankFallback = Counter.builder("rag.rerank.fallback")
            .description("rerank 降级次数（解析失败/结构异常/调用失败 → fusion_score 截断，Phase 4 簇①）").register(registry);
        this.ttft = Timer.builder("rag.ttft")
            .description("流式首 Token 延迟（Phase 4 簇② 4.3）")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
        this.chunkEdit = Counter.builder("rag.chunk.edit")
            .description("Chunk 运维编辑次数（Phase 4 簇③ 4.4）").register(registry);
        this.chunkSoftDelete = Counter.builder("rag.chunk.soft.delete")
            .description("Chunk 软删除次数（Phase 4 簇③ 4.4）").register(registry);
        this.chunkRestore = Counter.builder("rag.chunk.restore")
            .description("Chunk 软删恢复次数（Phase 4 簇③ 4.4）").register(registry);
        this.badCaseAnnotate = Counter.builder("rag.badcase.annotate")
            .description("Bad Case 根因标注次数（Phase 4 簇④ 4.7）").register(registry);
        this.badCaseReingest = Counter.builder("rag.badcase.reingest")
            .description("Bad Case Golden Set 回灌次数（Phase 4 簇④ 4.7）").register(registry);
        this.mcpSearch = Counter.builder("rag.mcp.search")
            .description("MCP search 工具调用次数（Phase 4 簇⑤ 4.10）").register(registry);
        this.mcpGetDocument = Counter.builder("rag.mcp.get_document")
            .description("MCP get_document 工具调用次数（Phase 4 簇⑤ 4.10）").register(registry);
        this.mcpAsk = Counter.builder("rag.mcp.ask")
            .description("MCP ask 工具调用次数（Phase 4 簇⑤ 4.10）").register(registry);
        this.guardrailMcpRateLimited = Counter.builder("rag.guardrail.mcp.ratelimited")
            .description("MCP 只读工具限流拒绝次数（安全簇② B3：search/get_document 独立配额桶）")
            .register(registry);
        // L2 语义判定计数族（安全簇⑤ E1，12.4 S5）：触发/拦截/观察/故障四态分列，
        // 零标签纪律；触发率 = triggered/request.total 经 Prometheus 表达式求
        this.guardrailL2Triggered = Counter.builder("rag.guardrail.l2.triggered")
            .description("L2 语义判定触发次数——REGEX 可疑且干词未命中进入二判（安全簇⑤ E1）").register(registry);
        this.guardrailL2Blocked = Counter.builder("rag.guardrail.l2.blocked")
            .description("L2 语义判定拦截次数——BLOCK 裁决 PROMPT_INJECTION 同语义拒答（安全簇⑤ E1）").register(registry);
        this.guardrailL2Suspect = Counter.builder("rag.guardrail.l2.suspect")
            .description("L2 语义判定观察次数——SUSPECT 裁决 FLAG 计数放行（安全簇⑤ E1）").register(registry);
        this.guardrailL2Error = Counter.builder("rag.guardrail.l2.error")
            .description("L2 语义判定故障次数——超时/失败/解析错误 fail-open 回落 L1 结论（安全簇⑤ E1）").register(registry);
        // 词表热重载计数族（安全簇⑥ F1，12.4 S8）：成败二态分列，零标签纪律
        this.guardrailReloadSucceeded = Counter.builder("rag.guardrail.reload.succeeded")
            .description("护栏词表热重载成功次数——快照原子替换 + 监听器推送（安全簇⑥ F1）").register(registry);
        this.guardrailReloadFailed = Counter.builder("rag.guardrail.reload.failed")
            .description("护栏词表热重载失败次数——装载失败 fail-keep 保旧快照（安全簇⑥ F1）").register(registry);
        // 词表运营 CRUD 计数族（v2.53 词表 DB 单轨）：create/update/delete 三态分列，零标签纪律
        this.guardrailRuleCreated = Counter.builder("rag.guardrail.rule.created")
            .description("护栏词表运营新建词项次数——CRUD API 写路径（v2.53 DB 单轨）").register(registry);
        this.guardrailRuleUpdated = Counter.builder("rag.guardrail.rule.updated")
            .description("护栏词表运营更新词项次数——CRUD API 写路径（v2.53 DB 单轨）").register(registry);
        this.guardrailRuleDeleted = Counter.builder("rag.guardrail.rule.deleted")
            .description("护栏词表运营删除词项次数——CRUD API 写路径（v2.53 DB 单轨）").register(registry);
        // 双供应商 SLA 计数族（Phase 4 簇⑥ 批4）：熔断三态迁移与备用接管事件，
        // 零标签纪律；主模型可用率/切换时序经面板与告警表达式消费
        this.routingCircuitOpened = Counter.builder("rag.routing.circuit.opened")
            .description("主模型熔断 OPEN 转入次数——达阈首开 + HALF_OPEN 试探失败重开（簇⑥ 批4 SLA）").register(registry);
        this.routingCircuitHalfOpened = Counter.builder("rag.routing.circuit.half-opened")
            .description("主模型 HALF_OPEN 试探次数——熔断窗口结束后首个请求试探（簇⑥ 批4 SLA）").register(registry);
        this.routingFallbackInvoked = Counter.builder("rag.routing.fallback.invoked")
            .description("备用模型接管请求次数——OPEN 直发 + 失败即切双路径（簇⑥ 批4 SLA）").register(registry);
        this.cacheHit = Counter.builder("rag.retrieval.cache.hit")
            .description("语义缓存命中次数——KNN top-1 相似度达阈（簇③ 5.6，13.3 预留位启用）").register(registry);
        this.cacheMiss = Counter.builder("rag.retrieval.cache.miss")
            .description("语义缓存未命中次数——索引空/相似度不足/运行期降级均计（簇③ 5.6）").register(registry);
        this.cacheInvalidated = Counter.builder("rag.retrieval.cache.invalidated")
            .description("语义缓存按文档失效删除条数——知识库变更事件驱动（簇③ 5.6）").register(registry);
    }

    /** Chunk 运维操作计数（Phase 4 簇③ 4.4：edit / soft_delete / restore） */
    public void recordChunkOps(String operation) {
        switch (operation) {
            case "edit" -> chunkEdit.increment();
            case "soft_delete" -> chunkSoftDelete.increment();
            case "restore" -> chunkRestore.increment();
            default -> { /* 未知操作不计——零标签纪律下的键收口 */ }
        }
    }

    /** Bad Case 运营闭环计数（Phase 4 簇④ 4.7：annotate 根因标注 / reingest Golden 回灌） */
    public void recordBadCaseOps(String operation) {
        switch (operation) {
            case "annotate" -> badCaseAnnotate.increment();
            case "reingest" -> badCaseReingest.increment();
            default -> { /* 未知操作不计——零标签纪律下的键收口 */ }
        }
    }

    /** MCP 三件套工具调用计数（Phase 4 簇⑤ 4.10：search / get_document / ask，调用审计的指标面） */
    public void recordMcpToolCall(String operation) {
        switch (operation) {
            case "search" -> mcpSearch.increment();
            case "get_document" -> mcpGetDocument.increment();
            case "ask" -> mcpAsk.increment();
            default -> { /* 未知操作不计——零标签纪律下的键收口 */ }
        }
    }

    /** MCP 只读工具限流拒绝计数（安全簇② B3）：超限拒绝事件入 Prometheus */
    public void recordMcpRateLimited() {
        guardrailMcpRateLimited.increment();
    }

    /** L2 语义判定触发计数（安全簇⑤ E1）：可疑请求进入二判（触发率分子） */
    public void recordL2Triggered() {
        guardrailL2Triggered.increment();
    }

    /** L2 语义判定拦截计数（安全簇⑤ E1）：BLOCK 裁决拒答（审计 REJECTED 另经 AuditTraceAdvisor 落库） */
    public void recordL2Blocked() {
        guardrailL2Blocked.increment();
    }

    /** L2 语义判定观察计数（安全簇⑤ E1）：SUSPECT 裁决 FLAG 放行 */
    public void recordL2Suspect() {
        guardrailL2Suspect.increment();
    }

    /** L2 语义判定故障计数（安全簇⑤ E1）：超时/失败/解析错误 fail-open 回落 L1 结论 */
    public void recordL2Error() {
        guardrailL2Error.increment();
    }

    /** 词表热重载成败计数（安全簇⑥ F1）：协调器按 reload 返回值落账 */
    public void recordGuardrailReload(boolean succeeded) {
        if (succeeded) {
            guardrailReloadSucceeded.increment();
        } else {
            guardrailReloadFailed.increment();
        }
    }

    /** 词表运营 CRUD 计数（v2.53 DB 单轨：create 新建 / update 更新 / delete 删除） */
    public void recordGuardrailOps(String operation) {
        switch (operation) {
            case "create" -> guardrailRuleCreated.increment();
            case "update" -> guardrailRuleUpdated.increment();
            case "delete" -> guardrailRuleDeleted.increment();
            default -> { /* 未知操作不计——零标签纪律下的键收口 */ }
        }
    }

    /** 流式首 Token 延迟（AgentController 流式路径：请求进入 → 首个非空 token，双链共记） */
    public void recordTtft(Duration elapsed) {
        ttft.record(elapsed);
    }

    /** 熔断 OPEN 转入计数（簇⑥ 批4 SLA）：SmartRoutingChatModel 达阈首开/试探失败重开 */
    public void recordCircuitOpened() {
        routingCircuitOpened.increment();
    }

    /** HALF_OPEN 试探计数（簇⑥ 批4 SLA）：熔断窗口结束后首个请求试探主模型 */
    public void recordCircuitHalfOpened() {
        routingCircuitHalfOpened.increment();
    }

    /** 备用模型接管计数（簇⑥ 批4 SLA）：OPEN 直发与失败即切双路径各计一次 */
    public void recordFallbackInvoked() {
        routingFallbackInvoked.increment();
    }

    /** 语义缓存查找计数（簇③ 5.6）：hit = KNN top-1 相似度达阈；命中率 = hit/(hit+miss) */
    public void recordCacheLookup(boolean hit) {
        (hit ? cacheHit : cacheMiss).increment();
    }

    /** 语义缓存按文档失效计数（簇③ 5.6）：一次事件删除的条目条数 */
    public void recordCacheInvalidated(int count) {
        cacheInvalidated.increment(count);
    }

    /** 用户反馈计数（3.17 反馈 API 接线点） */
    public void recordFeedback(boolean positive) {
        (positive ? feedbackLike : feedbackDislike).increment();
    }

    /** 检索执行 + 命中计数（hit = final 重排序列非空） */
    public void recordRetrieval(boolean hit) {
        retrievalTotal.increment();
        if (hit) {
            retrievalHit.increment();
        }
    }

    /** 混合检索耗时（HybridDocumentRetriever 真实耗时） */
    public void recordRetrievalLatency(Duration elapsed) {
        retrievalLatency.record(elapsed);
    }

    /** 工具调用计数：按状态分桶（EXECUTED 成功 / PENDING_APPROVAL 挂起待审） */
    public void recordToolCall(String status) {
        toolCallTotal.increment();
        if (RetrievalContext.ToolCall.STATUS_EXECUTED.equals(status)) {
            toolCallSuccess.increment();
        } else if (RetrievalContext.ToolCall.STATUS_PENDING_APPROVAL.equals(status)) {
            toolCallPending.increment();
        }
    }

    /** Token 消耗累加（TokenBudgetAdvisor after 回写） */
    public void addTokens(long tokens) {
        tokenTotal.increment(tokens);
    }

    /** Token 预算拒绝计数（TokenBudgetAdvisor before 超额）——成本域与安全域双计数 */
    public void recordTokenBudgetRejected() {
        tokenBudgetRejected.increment();
        guardrailTokenBudget.increment();
    }

    /** 意图路由分流计数（5.4 收窄版）：闲聊/元问题旁路检索。分流比 = chitchat/(chitchat+knowledge) */
    public void recordRoutingChitchat() {
        routingChitchat.increment();
    }

    /** 意图路由分流计数（5.4 收窄版）：知识问答走完整检索（含 fail-open 回落） */
    public void recordRoutingKnowledge() {
        routingKnowledge.increment();
    }

    /** 护栏命中计数（簇⑤ B2 S3）：Prompt 注入拦截（InputSanitizeAdvisor 抛 PROMPT_INJECTION 前） */
    public void recordInjectionBlocked() {
        guardrailInjectionBlocked.increment();
    }

    /**
     * 护栏命中计数（簇⑤ B2 S3 / 安全簇③ C1/C2）：PII 掩码触发
     * （InputSanitizeAdvisor，非拒绝型干预）——总项恒计（语义不变：一次掩码
     * 干预计一次），命中类型另计对应类型子项（零标签纪律，类型枚举 switch
     * 收口；NAME/ADDRESS 为 C3 登记项无识别器，不可达只计总项）。
     */
    public void recordPiiMasked(Collection<PiiType> hitTypes) {
        guardrailPiiMasked.increment();
        for (PiiType type : hitTypes) {
            switch (type) {
                case PHONE -> guardrailPiiMaskedPhone.increment();
                case ID_CARD -> guardrailPiiMaskedIdCard.increment();
                case EMAIL -> guardrailPiiMaskedEmail.increment();
                case BANK_CARD -> guardrailPiiMaskedBankCard.increment();
                case LANDLINE -> guardrailPiiMaskedLandline.increment();
                case LICENSE_PLATE -> guardrailPiiMaskedLicensePlate.increment();
                case IPV4 -> guardrailPiiMaskedIpv4.increment();
                default -> { /* C3 登记项无识别器——只计总项 */ }
            }
        }
    }

    /**
     * 输出 PII 回显观察（安全簇③，簇① T5 钩子闭环）：回答中检出未掩码强形态
     * PII → FLAG 观察起步——只计数 + warn 日志（类型事实），不替换不阻断
     * （专项方案 §4.1 A3：验证误报后再定动作）。
     */
    public void recordOutputPiiEcho() {
        guardrailOutputPiiEcho.increment();
    }

    /**
     * 间接注入扫描命中计数（安全簇④ D1）：召回证据经注入词表归一化检测视图
     * 命中——按命中条数 increment（warn/exclude 两策略共用；exclude 档另计
     * {@link #recordIndirectExcluded}）。零标签纪律：不带租户/族系标签。
     */
    public void recordIndirectFlagged(int hitCount) {
        guardrailIndirectFlagged.increment(hitCount);
    }

    /** 间接注入扫描 exclude 策略剔除计数（安全簇④ D1）：命中证据被剔出 grounding */
    public void recordIndirectExcluded(int excludedCount) {
        guardrailIndirectExcluded.increment(excludedCount);
    }

    /** 入库打标降权计数（安全簇④ D2）：injection_hit chunk 融合分衰减生效条数 */
    public void recordInjectionHitDemoted(int demotedCount) {
        retrievalInjectionHitDemoted.increment(demotedCount);
    }

    /**
     * 护栏命中计数（簇⑤ B2 S3 / 安全簇① T5）：输出敏感词表整段替换
     * （OutputGuardrailAdvisor，非拒绝型干预）——总项恒计，命中词项族系属
     * OutputFamily 三分类时另计对应分类子项（未知族系只计总项）。
     */
    public void recordOutputReplaced(String family) {
        guardrailOutputReplaced.increment();
        if (family == null) {
            return;
        }
        switch (family.trim().toUpperCase()) {
            case "BUSINESS_CONFIDENTIAL" -> guardrailOutputReplacedBusinessConfidential.increment();
            case "COMPLIANCE_SENSITIVE" -> guardrailOutputReplacedComplianceSensitive.increment();
            case "COMPETITOR_COMPARISON" -> guardrailOutputReplacedCompetitorComparison.increment();
            default -> { /* 未知/UNCLASSIFIED 族系只计总项——零标签纪律下的键收口 */ }
        }
    }

    /** 护栏命中计数（安全簇① T5）：系统提示金丝雀回显——确证提示泄露，整段替换 */
    public void recordOutputCanary() {
        guardrailOutputCanary.increment();
    }

    /**
     * 护栏 FLAG 观察档计数（安全簇① T7，词表变更流程定案 A4）：命中只计数 +
     * 审计标记、不拒绝。side 取 {@link #SIDE_INPUT}/{@link #SIDE_OUTPUT}，
     * family 为中性枚举名（null/空白/未知族系 → UNCLASSIFIED 兜底桶）；
     * 未知 side 不计（调用方固定为两侧护栏 advisor）。
     */
    public void recordFlagged(String side, String family) {
        Counter counter = guardrailFlagged.get(flagKey(side, canonicalFamily(family)));
        if (counter == null) {
            counter = guardrailFlagged.get(flagKey(side, FAMILY_UNCLASSIFIED));
        }
        if (counter != null) {
            counter.increment();
        }
    }

    private static String flagKey(String side, String family) {
        return side + ":" + family;
    }

    private static String canonicalFamily(String family) {
        return (family == null || family.isBlank()) ? FAMILY_UNCLASSIFIED : family.trim().toUpperCase();
    }

    /** 护栏命中计数（簇⑤ B2 S3）：租户限流拒绝（RateLimitAdvisor 抛 RATE_LIMITED 前） */
    public void recordRateLimited() {
        guardrailRateLimited.increment();
    }

    /** 文档增量重入库计数（簇⑥ C1）：started 占用成功 / succeeded/failed 终态回调 */
    public void recordReindexStarted() {
        documentReindexStarted.increment();
    }

    /** 文档增量重入库计数（簇⑥ C1） */
    public void recordReindexOutcome(boolean succeeded) {
        (succeeded ? documentReindexSucceeded : documentReindexFailed).increment();
    }

    /**
     * 请求结果计数（Phase 4 簇①，告警分母）：与审计三态同语义——
     * error=null 计 total；BusinessException（护栏/配额拒绝，审计 REJECTED）计 rejected；
     * 其他异常（供应商/系统，审计 ERROR）计 error。拒绝率/错误率 = 对应计数/total。
     */
    public void recordRequestOutcome(Throwable error) {
        requestTotal.increment();
        if (error instanceof BusinessException) {
            requestRejected.increment();
        } else if (error != null) {
            requestError.increment();
        }
    }

    /** rerank 执行/降级计数（Phase 4 簇①）：降级率 = fallback/total */
    public void recordRerank(boolean fallback) {
        rerankTotal.increment();
        if (fallback) {
            rerankFallback.increment();
        }
    }
}
