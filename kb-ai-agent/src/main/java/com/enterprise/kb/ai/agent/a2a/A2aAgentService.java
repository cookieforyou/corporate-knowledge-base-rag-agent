package com.enterprise.kb.ai.agent.a2a;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.ai.service.RagChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * A2A 对话服务（Phase5簇⑥ 批2，实施方案 §4.2 治理层——协议层判负回落自研后
 * 类名对齐 DingTalkChatService 形态，不吃 SDK AgentExecutor 接口）
 *
 * <p><b>链路形态</b>：复用 {@link RagChatService#chatRag} 同步阻塞式（全护栏/
 * 审计/记忆/租户隔离链零减配，复用即继承）；会话前缀 {@code a2a-{contextId}}
 * （v1.0 spec §3.4.1：contextId 逻辑分组同一会话上下文，记忆域与前端/MCP/钉钉
 * 隔离——A2A client 携同一 contextId 续问即多轮延续）。
 *
 * <p><b>同步应答语义</b>：与钉钉（webhook 异步推回）不同，A2A message 调用是
 * 同步 HTTP 请求——HTTP 线程直接阻塞 chatRag（虚拟线程使能，无双层提交/
 * 超时放弃形态的必要；LLM 侧超时由模型客户端自身承载）。
 *
 * <p><b>编排序</b>：request 计数 → 限流（超限 RATE_LIMITED 上抛，已计
 * rate-limited）→ 入口轻审计 → chatRag 委派（duration 计时）→ answer 返回；
 * 对话链异常（注入拒绝/模型故障）→ error 计数后原样上抛（协议层转 JSON-RPC
 * error，话术固定零泄露）。
 */
@Slf4j
@Component
public class A2aAgentService {

    static final String SESSION_PREFIX = "a2a-";

    private final RagChatService ragChatService;
    private final A2aRateLimiter rateLimiter;
    private final A2aAuditRecorder auditRecorder;
    private final AiBusinessMetrics metrics;

    public A2aAgentService(RagChatService ragChatService,
                           A2aRateLimiter rateLimiter,
                           A2aAuditRecorder auditRecorder,
                           AiBusinessMetrics metrics) {
        this.ragChatService = ragChatService;
        this.rateLimiter = rateLimiter;
        this.auditRecorder = auditRecorder;
        this.metrics = metrics;
    }

    /**
     * 处理一条 SendMessage 请求（HTTP 请求线程同步执行）。
     *
     * @param query     消息文本（parts 拼接，协议层已校验非空）
     * @param contextId 会话上下文 ID（客户端携带或协议层生成，必非空）
     * @param ctx       身份上下文（A2aIdentityGuard 物化，traceId 已设）
     * @return 答案正文（含 [ref-N] 锚定——检索链 formatter 内建，A2A client 消费）
     */
    public String handle(String query, String contextId, RetrievalContext ctx) {
        metrics.recordA2aRequest();
        rateLimiter.acquire(ctx.getTenantId());
        auditRecorder.record(query, ctx);
        String sessionId = SESSION_PREFIX + contextId;
        long startNanos = System.nanoTime();
        try {
            String answer = ragChatService.chatRag(query, sessionId, ctx);
            metrics.recordA2aChatDuration(Duration.ofNanos(System.nanoTime() - startNanos));
            return answer;
        } catch (Exception e) {
            metrics.recordA2aError();
            log.warn("A2A 对话失败（sessionId={}）: {}", sessionId, e.getMessage());
            throw e;
        }
    }
}
