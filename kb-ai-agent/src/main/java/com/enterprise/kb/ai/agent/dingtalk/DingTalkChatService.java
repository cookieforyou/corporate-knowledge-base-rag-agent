package com.enterprise.kb.ai.agent.dingtalk;

import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.ai.service.RagChatService;
import com.enterprise.kb.commons.constant.Constants;
import com.enterprise.kb.commons.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 钉钉群机器人对话服务（Phase5簇⑥ 5.12，实施方案 D2-A/D3-A 定案）
 *
 * <p><b>链路形态（D3-A）</b>：mode 固定 rag——复用 {@link RagChatService#chatRag}
 * 同步阻塞式（全护栏/审计/记忆/租户隔离链零减配，复用即继承）；会话前缀
 * {@code dingtalk-{conversationId}}（钉钉会话 ID 天然多轮键，记忆域与前端/MCP
 * 隔离）。回复 = answer 正文 + 尾部溯源附录（markdown 卡片）。
 *
 * <p><b>身份（D2-A）</b>：tenantId = 配置绑定的服务账号租户（启动期 fail-closed）；
 * userId = senderStaffId 透传（审计/护栏用户维度，缺失回落匿名）。
 *
 * <p><b>溯源附录</b>：{@code [ref-N]} 锚定与编号化 formatter 对齐——formatter
 * （v2.15）编号 = final trace 序列（RerankDocumentPostProcessor 落
 * {@code TRACE_SOURCE_FINAL}），本服务按同序列枚举即得精确映射；final 缺席
 * （异常/空证据拒答路径）则省略附录。
 *
 * <p><b>异步与超时</b>：SDK 消费线程只做编排即返回；对话在钉钉执行器（虚拟线程）
 * 上经双层提交获得可放弃的超时控制——超时仅放弃等待回复话术，后台对话
 * <b>不中断</b>（非打断式，坑位㊶ 同哲学：中断会击穿 Neo4j 驱动等阻塞调用），
 * 结果到达即丢弃（webhook 未过期也放弃——避免迟到的双回复）。
 */
@Slf4j
@Component
public class DingTalkChatService {

    static final String SESSION_PREFIX = "dingtalk-";
    static final String ANONYMOUS_USER = "dingtalk-anonymous";
    static final int TITLE_MAX_CHARS = 20;
    static final int CHUNK_ID_SHORT = 8;

    private final RagChatService ragChatService;
    private final DingTalkRateLimiter rateLimiter;
    private final DingTalkAuditRecorder auditRecorder;
    private final DingTalkReplyClient replyClient;
    private final AiBusinessMetrics metrics;
    private final DingTalkProperties properties;
    private final ExecutorService executor;

    public DingTalkChatService(RagChatService ragChatService,
                               DingTalkRateLimiter rateLimiter,
                               DingTalkAuditRecorder auditRecorder,
                               DingTalkReplyClient replyClient,
                               AiBusinessMetrics metrics,
                               DingTalkProperties properties,
                               @Qualifier(Constants.BeanNames.DINGTALK_EXECUTOR) ExecutorService executor) {
        this.ragChatService = ragChatService;
        this.rateLimiter = rateLimiter;
        this.auditRecorder = auditRecorder;
        this.replyClient = replyClient;
        this.metrics = metrics;
        this.properties = properties;
        this.executor = executor;
    }

    /**
     * 处理一条 @ 消息（SDK 消费线程调用——只做提取/限流/审计/提交即返回）。
     * 非文本消息（富文本/图片等）忽略：{@code text.content} 仅 @ 文本消息在场。
     */
    public void handle(ChatbotMessage message) {
        String text = extractText(message);
        String sessionWebhook = message.getSessionWebhook();
        if (text == null || text.isBlank()) {
            log.debug("钉钉消息缺文本载荷（msgId={}），忽略", message.getMsgId());
            return;
        }
        if (sessionWebhook == null || sessionWebhook.isBlank()) {
            log.warn("钉钉消息缺 sessionWebhook 无法回复（msgId={}），丢弃", message.getMsgId());
            return;
        }
        metrics.recordDingTalkMessage();

        String tenantId = properties.getTenantId();
        String userId = resolveUserId(message);
        String sessionId = SESSION_PREFIX + message.getConversationId();
        // 当轮唯一 ctx：入口审计轻行与对话链 AuditTraceAdvisor 审计行凭同一 traceId 关联
        RetrievalContext ctx = newContext(tenantId, userId);
        try {
            rateLimiter.acquire(tenantId);
        } catch (BusinessException e) {
            replySafely(sessionWebhook, "提示", "请求过于频繁，请稍后再试。");
            return;
        }
        auditRecorder.record(text, ctx);
        executor.execute(() -> runChat(text, sessionId, sessionWebhook, ctx));
    }

    /** 对话执行（钉钉执行器虚拟线程）：双层提交获得可放弃超时，回复经 webhook 异步推回 */
    private void runChat(String text, String sessionId, String sessionWebhook, RetrievalContext ctx) {
        long startNanos = System.nanoTime();
        Duration timeout = Duration.ofSeconds(properties.getChatTimeoutSeconds());
        // ctx 沿用 handle 创建的当轮实例：后台线程填充快照，本线程流末读溯源——纯实例跨线程传递（参数链形态）
        try {
            Future<String> future = executor.submit(() -> ragChatService.chatRag(text, sessionId, ctx));
            String answer;
            try {
                answer = future.get(timeout.toSeconds(), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                // 非打断式：future 不 cancel，后台对话自然完成即丢弃（避免迟到双回复）
                log.warn("钉钉对话超时（>{}s，sessionId={}），回复超时话术", timeout.toSeconds(), sessionId);
                metrics.recordDingTalkError();
                replySafely(sessionWebhook, titleOf(text), "回答生成超时，请稍后重试。");
                return;
            }
            replySafely(sessionWebhook, titleOf(text), buildMarkdown(answer, ctx));
            metrics.recordDingTalkChatDuration(Duration.ofNanos(System.nanoTime() - startNanos));
            metrics.recordDingTalkReplied();
        } catch (Exception e) {
            log.warn("钉钉对话失败（sessionId={}）: {}", sessionId, e.getMessage());
            metrics.recordDingTalkError();
            replySafely(sessionWebhook, titleOf(text), "服务暂时不可用，请稍后重试。");
        }
    }

    /** markdown 正文 = 答案 + 溯源附录（final trace 序列 [ref-N] 精确映射；final 缺席省略） */
    private String buildMarkdown(String answer, RetrievalContext ctx) {
        if (ctx == null) {
            return answer;
        }
        List<Document> finalDocs = ctx.getTraceSummary().stream()
            .filter(entry -> Constants.Retrieval.TRACE_SOURCE_FINAL.equals(entry.source()))
            .findFirst()
            .map(RetrievalContext.TraceEntry::documents)
            .orElse(List.of());
        if (finalDocs.isEmpty()) {
            return answer;
        }
        StringBuilder sb = new StringBuilder(answer).append("\n\n---\n**溯源**\n");
        for (int i = 0; i < finalDocs.size(); i++) {
            Map<String, Object> meta = finalDocs.get(i).getMetadata();
            String fileName = meta.get(Constants.Retrieval.META_FILE_NAME) == null
                ? "未命名文档" : meta.get(Constants.Retrieval.META_FILE_NAME).toString();
            Object pageNum = meta.get(Constants.Retrieval.META_PAGE_NUM);
            Object chunkId = meta.get(Constants.Retrieval.META_CHUNK_ID);
            sb.append("- [ref-").append(i + 1).append("] ").append(fileName);
            // 页码 >0 才显示（E2E 实证：Markdown 语料 ETL 写 page_num=0 占位——无信息量；
            // 同文档部分 chunk 键缺失为 null。对齐前端溯源面板 v-if 过滤语义：0/null 均省略）
            if (pageNum instanceof Number n && n.intValue() > 0) {
                sb.append("（p.").append(n.intValue()).append("）");
            }
            if (chunkId != null) {
                String id = chunkId.toString();
                sb.append(" `").append(id, 0, Math.min(CHUNK_ID_SHORT, id.length())).append("`");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 回复失败只计错不击穿（消息已处理，不可重放） */
    private void replySafely(String sessionWebhook, String title, String text) {
        try {
            replyClient.replyMarkdown(sessionWebhook, title, text);
        } catch (Exception e) {
            metrics.recordDingTalkError();
            log.warn("钉钉回复失败（webhook 过期或网络异常）: {}", e.getMessage());
        }
    }

    private RetrievalContext newContext(String tenantId, String userId) {
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId(tenantId);
        ctx.setUserId(userId);
        ctx.setTraceId(UUID.randomUUID().toString());
        return ctx;
    }

    private static String extractText(ChatbotMessage message) {
        return message.getText() == null || message.getText().getContent() == null
            ? null : message.getText().getContent().trim();
    }

    private static String resolveUserId(ChatbotMessage message) {
        return message.getSenderStaffId() == null || message.getSenderStaffId().isBlank()
            ? ANONYMOUS_USER : message.getSenderStaffId();
    }

    /** 卡片标题：问题摘要（截断保卡片可读） */
    private static String titleOf(String text) {
        return text.length() <= TITLE_MAX_CHARS ? text : text.substring(0, TITLE_MAX_CHARS) + "…";
    }
}
