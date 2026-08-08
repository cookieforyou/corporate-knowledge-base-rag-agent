package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 意图分类 Advisor（5.4 收窄版提前落地，设计文档 11.4 锚点）—— Order 440
 *
 * <p>rag 链内免检索短路：MessageChatMemoryAdvisor(400) 之后、RetrievalGateAdvisor(500)
 * 之前执行，分类结果经请求级 {@link RetrievalContext} 参数链下传：
 * <ul>
 *   <li><b>CHITCHAT</b>（寒暄/致谢/道别/对话元问题）→ {@code skipRetrieval=true}，
 *       门控旁路改写/双路检索/重排/grounding 全套，模型携多轮记忆直答——
 *       根治 grounding 强约束（「必须且只能基于【参考资料】」）压过历史记忆
 *       导致的元问题拒答（2026-08-08 E2E 实锤）与简单提问的检索开销</li>
 *   <li><b>KNOWLEDGE</b> → 分类器同步产出的改写文本**预写入**
 *       {@code rewrittenQuery}，下游 RewriteCapturingQueryTransformer 识别后
 *       跳过自身 LLM 调用——分类与多轮指代消解合并为**同一次** LLM 调用，
 *       知识问零新增延迟</li>
 * </ul>
 *
 * <p>双层快判：L1 正则快路覆盖高频纯寒暄（零 LLM 调用）；L2 结构化分类
 * （{@code entity(IntentResult.class)}）兜住元问题与库外问题。分类器经
 * ChatClient.Builder 构建——与 RewriteQueryTransformer 同款装配，自动获得
 * smartRoutingChatModel 主备熔断。
 *
 * <p><b>fail-open 纪律</b>：分类异常/解析失败/未知 intent 一律回落 KNOWLEDGE
 * （最坏=现状完整检索），绝不因分类故障击穿问答。
 */
@Slf4j
public class QueryRoutingAdvisor implements BaseAdvisor {

    /** L1 规则快路：整句全匹配的纯寒暄/致谢/道别/助手元问题（保守边界防误判） */
    private static final Pattern CHITCHAT_PATTERN = Pattern.compile(
        "^(你好|您好|hello|hi|hey|哈喽|嗨|谢谢|感谢|多谢|thanks|thank you|thx|" +
        "再见|拜拜|bye|goodbye|晚安|早安|早上好|晚上好|好的|嗯|ok|okay|在吗|" +
        "你是谁|你叫什么|你叫什么名字|你能做什么|你会什么|你能干什么)$",
        Pattern.CASE_INSENSITIVE);

    /** 规则快路长度上限：超出必走 L2 分类（「你好，请问年假政策…」类混合句不误判） */
    private static final int RULE_MAX_LENGTH = 15;

    /** 历史消息单条截断长度：分类只需语义轮廓，防长回答撑爆分类 prompt */
    private static final int HISTORY_MESSAGE_MAX_CHARS = 300;

    private static final String CLASSIFIER_PROMPT = """
        你是企业知识库问答系统的意图分类器。根据对话历史与当前用户消息判定意图，并产出检索用查询。

        【意图定义】
        - CHITCHAT：寒暄、致谢、道别、对对话本身的元问题（如「我刚才问了什么」「你刚才说了什么」）、\
        以及与知识库内容明显无关且无需检索即可回应的请求
        - KNOWLEDGE：需要查询企业知识库才能回答的事实性/政策性/流程性问题（含基于上文的追问）

        【判定纪律】拿不准时一律 KNOWLEDGE——误走检索仅多付开销，误免检索损失回答质量。

        【rewrittenQuery 规则】intent=KNOWLEDGE 时必填：当前消息含指代/省略（如「它的」「那第二点呢」）\
        时给出结合历史消解后的完整查询；否则原样返回当前消息。intent=CHITCHAT 时置 null。
        """;

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final AiBusinessMetrics metrics;
    private final boolean enabled;
    private final int historySize;

    public QueryRoutingAdvisor(ChatClient.Builder chatClientBuilder,
                               ChatMemory chatMemory,
                               AiBusinessMetrics metrics,
                               boolean enabled,
                               int historySize) {
        this.chatClient = chatClientBuilder.build();
        this.chatMemory = chatMemory;
        this.metrics = metrics;
        this.enabled = enabled;
        this.historySize = historySize;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!enabled) {
            return request;
        }
        if (!(request.context().get(RetrievalContext.CONTEXT_KEY) instanceof RetrievalContext ctx)) {
            return request; // 防御：非 Web 入口无检索上下文，透传
        }
        String userText = request.prompt().getUserMessage().getText();
        if (userText == null || userText.isBlank()) {
            return request;
        }
        String trimmed = userText.trim();

        // L1 规则快路：纯寒暄整句匹配，零 LLM 调用
        if (trimmed.length() <= RULE_MAX_LENGTH && CHITCHAT_PATTERN.matcher(trimmed).matches()) {
            ctx.setSkipRetrieval(true);
            metrics.recordRoutingChitchat();
            return request;
        }

        // L2 合并分类（意图 + 检索查询改写一次调用产出）
        try {
            IntentResult result = classify(loadHistory(request), trimmed);
            if (result != null && IntentResult.INTENT_CHITCHAT.equalsIgnoreCase(result.intent())) {
                ctx.setSkipRetrieval(true);
                metrics.recordRoutingChitchat();
                return request;
            }
            // KNOWLEDGE / 未知 intent / null 结果：fail-open 走完整检索
            ctx.setRewrittenQuery(
                result != null && result.rewrittenQuery() != null && !result.rewrittenQuery().isBlank()
                    ? result.rewrittenQuery().trim()
                    : trimmed);
            metrics.recordRoutingKnowledge();
        } catch (Exception e) {
            log.warn("意图分类失败，fail-open 回落完整检索链路", e);
            metrics.recordRoutingKnowledge();
        }
        return request;
    }

    /** 经 advisor 参数取会话 ID 直读共享记忆（与 MessageChatMemoryAdvisor 同 Bean；当前轮尚未入忆，读到纯历史） */
    private List<Message> loadHistory(ChatClientRequest request) {
        String conversationId = Objects.toString(request.context().get(ChatMemory.CONVERSATION_ID), null);
        if (conversationId == null) {
            return List.of();
        }
        List<Message> all = chatMemory.get(conversationId);
        int from = Math.max(0, all.size() - historySize);
        return all.subList(from, all.size());
    }

    private IntentResult classify(List<Message> history, String currentQuery) {
        StringBuilder sb = new StringBuilder(CLASSIFIER_PROMPT);
        if (!history.isEmpty()) {
            sb.append("\n【对话历史（最近 ").append(history.size()).append(" 条）】\n");
            for (Message message : history) {
                sb.append("- ").append(message.getMessageType().getValue())
                    .append(": ").append(truncate(message.getText())).append('\n');
            }
        }
        sb.append("\n【当前用户消息】\n").append(currentQuery);

        return chatClient.prompt()
            .user(sb.toString())
            .call()
            .entity(IntentResult.class);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= HISTORY_MESSAGE_MAX_CHARS
            ? text : text.substring(0, HISTORY_MESSAGE_MAX_CHARS) + "…";
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response; // 分类只作用于 before 阶段
    }

    /** 11.2 链序表（v2.13）：Memory(400) 之后、RetrievalTrace(450) 之前 */
    @Override
    public int getOrder() {
        return 440;
    }
}
