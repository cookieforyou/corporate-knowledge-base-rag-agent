package com.enterprise.kb.ai.config;

import io.micrometer.common.KeyValues;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 内容捕获型 ChatModel ObservationConvention（Phase 4 簇①）
 *
 * <p><b>背景（实证）</b>：Spring AI 2.0 GA 的 {@code spring.ai.chat.observations.log-prompt /
 * log-completion} 只把内容打到应用日志（纯日志 handler，TracingAware 包装仅补作用域），
 * 内容不进 span——LLM 观测平台侧 generation input/output 恒空（Langfuse 官方
 * Spring AI 集成文档登记的已知缺口）。
 *
 * <p><b>形态选型（实证否掉打标 handler 路径）</b>：独立 ObservationHandler.onStop
 * 直接 {@code span.tag(...)} 与 tracing handler 的 span 结束存在顺序竞态（先结束则
 * 打标静默丢失）。改经 convention 高基数 KeyValue：observation 停止时先收集 KeyValue、
 * tracing handler 统一落 span attribute 后才结束 span——时序确定性无竞态。
 *
 * <p><b>键名契约</b>：{@code gen_ai.prompt} / {@code gen_ai.completion} 为 Langfuse
 * OTLP 映射契约属性（input/output 映射优先级表在档，13 章 v2.30）。
 *
 * <p><b>纪律</b>：仅内容捕获开关开启时注册（SmartRoutingConfig 条件装配）；
 * 截断上限防 span 属性膨胀；KeyValue 值禁 null（Document metadata 同款纪律延展）。
 */
public class ContentCapturingChatModelObservationConvention extends DefaultChatModelObservationConvention {

    /** Langfuse OTLP 映射契约：generation input ← 该 attribute */
    public static final String KEY_PROMPT = "gen_ai.prompt";
    /** Langfuse OTLP 映射契约：generation output ← 该 attribute */
    public static final String KEY_COMPLETION = "gen_ai.completion";
    /** 内容截断上限（字符）：防单 span 属性过大撞观测平台体积限制 */
    static final int MAX_CONTENT_CHARS = 32_000;

    @Override
    public KeyValues getHighCardinalityKeyValues(ChatModelObservationContext context) {
        KeyValues keyValues = super.getHighCardinalityKeyValues(context);
        String prompt = renderPrompt(context.getRequest());
        if (prompt != null && !prompt.isEmpty()) {
            keyValues = keyValues.and(KEY_PROMPT, truncate(prompt));
        }
        String completion = renderCompletion(context.getResponse());
        if (completion != null && !completion.isEmpty()) {
            keyValues = keyValues.and(KEY_COMPLETION, truncate(completion));
        }
        return keyValues;
    }

    /** prompt 渲染：逐条消息 [role] content 行式拼接（含系统提示与历史，完整还原模型输入） */
    private static String renderPrompt(Prompt prompt) {
        if (prompt == null || prompt.getInstructions() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Message message : prompt.getInstructions()) {
            String text = message.getText();
            if (text == null || text.isEmpty()) {
                continue;
            }
            sb.append('[').append(message.getMessageType().getValue()).append("] ")
                .append(text).append('\n');
        }
        return sb.toString();
    }

    /** completion 渲染：多 generation 文本拼接（常规单 generation 即回答全文） */
    private static String renderCompletion(ChatResponse response) {
        if (response == null || response.getResults() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Generation generation : response.getResults()) {
            if (generation.getOutput() != null && generation.getOutput().getText() != null) {
                sb.append(generation.getOutput().getText());
            }
        }
        return sb.toString();
    }

    private static String truncate(String content) {
        return content.length() <= MAX_CONTENT_CHARS ? content
            : content.substring(0, MAX_CONTENT_CHARS) + "...[truncated]";
    }
}
