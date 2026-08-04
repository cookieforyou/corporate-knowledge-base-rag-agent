package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.commons.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 输入安全护栏（设计文档 12.1，任务 3.5）—— PII 脱敏 + Prompt 注入检测
 *
 * <p>Order 300：先于记忆 Advisor(400) 执行 before()——脱敏后的文本才进记忆仓储，
 * 避免 PII 落库（11.2 链序表注）。
 *
 * <p><b>v2.4 实现期修正</b>（12 章草稿两处失效 API）：① BaseAdvisor 是接口，
 * 草稿 {@code extends} 改 {@code implements}；② {@code request.userText()} 不存在
 * （ChatClientRequest 为 record，仅 prompt()/context()），用户文本经
 * {@link Prompt#augmentUserMessage(String)} 替换末条用户消息后重建请求。
 *
 * <p>L1 形态（12.1.1 三层防御第一层）：正则快筛。数字类模式加边界断言，
 * 避免长数字串（订单号等）内部误匹配。语义化/多语言注入的 L2（LLM 辅助判定）
 * 与 L3（专用分类器）为升级路线。
 */
@Slf4j
@Component
public class InputSanitizeAdvisor implements BaseAdvisor {

    /** Prompt 注入检测关键词（L1 明文攻击模式，12.1） */
    private static final List<String> INJECTION_PATTERNS = List.of(
        "ignore previous", "ignore all", "forget everything",
        "system prompt", "you are now", "new instructions",
        "忽略之前的", "忘记所有", "新的指令", "你的系统提示词");

    // PII 正则（边界断言防长数字串内部误匹配）
    private static final Pattern PHONE_PATTERN =
        Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD_PATTERN =
        Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?![\\dXx])");
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}");

    private static final String PHONE_MASK = "1***-****-****";
    private static final String ID_CARD_MASK = "******************";
    private static final String EMAIL_MASK = "***@***.***";

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        Prompt prompt = request.prompt();
        String userText = prompt.getUserMessage() != null ? prompt.getUserMessage().getText() : null;
        if (userText == null || userText.isEmpty()) {
            return request;
        }

        // 1. Prompt 注入检测——命中即拒，不进入后续链路（同步 400 / 流式 ERROR 事件）
        if (detectInjection(userText)) {
            log.warn("检测到 Prompt 注入攻击，请求已拦截");
            throw new BusinessException("PROMPT_INJECTION", "检测到 Prompt 注入攻击，请求已被拦截");
        }

        // 2. PII 脱敏（幂等：掩码形态不会被二次匹配）
        String sanitized = sanitize(userText);
        if (sanitized.equals(userText)) {
            return request;
        }
        Prompt mutated = prompt.augmentUserMessage(sanitized);
        return new ChatClientRequest(mutated, request.context());
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public int getOrder() {
        return 300;
    }

    private static boolean detectInjection(String text) {
        String lower = text.toLowerCase();
        return INJECTION_PATTERNS.stream().anyMatch(lower::contains);
    }

    static String sanitize(String text) {
        String result = PHONE_PATTERN.matcher(text).replaceAll(PHONE_MASK);
        result = ID_CARD_PATTERN.matcher(result).replaceAll(ID_CARD_MASK);
        result = EMAIL_PATTERN.matcher(result).replaceAll(EMAIL_MASK);
        return result;
    }
}
