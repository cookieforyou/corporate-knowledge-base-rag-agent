package com.enterprise.kb.eval.it.stub;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 可编程 ChatModel 桩（簇⑥ D3）——替代真实 DeepSeek/备用模型，与生产链路
 * 字节码路径一致（SmartRoutingChatModel 包装、全 Advisor 链穿越不变）。
 *
 * <p>能力：
 * <ul>
 *   <li>{@code defaultAnswer} 主回答文本（同步/流式共用）</li>
 *   <li>{@code responseRouter} 按请求 user 文本路由回答——意图分类器调用
 *       （user 文本含「意图分类器」标记）借此返回 IntentResult JSON</li>
 *   <li>{@code usage} token 计账（TokenBudgetIT 断言账本累加）</li>
 *   <li>请求记录：{@link #userTexts}（末条 user 消息）与 {@link #promptSnapshots}
 *       （全消息列表文本）——grounding/记忆注入/拒答模板断言的证据通道</li>
 * </ul>
 *
 * <p>流式语义对齐主模型（stream_options.include_usage 形态）：分块下发，
 * 仅末块携带真实 Usage，前置块 Usage 为 0。
 */
public class StubChatModel implements ChatModel {

    public static final String DEFAULT_ANSWER = "这是桩模型的默认回答。";

    private volatile String defaultAnswer = DEFAULT_ANSWER;
    private volatile int promptTokens = 100;
    private volatile int completionTokens = 50;
    private volatile Function<String, String> responseRouter = null;
    private volatile int streamChunkSize = 10;

    /** 每次调用记录的 user 消息文本（按调用序） */
    public final List<String> userTexts = new CopyOnWriteArrayList<>();
    /** 每次调用记录的全消息列表文本（含系统/历史/用户消息，按调用序） */
    public final List<List<String>> promptSnapshots = new CopyOnWriteArrayList<>();

    public void setDefaultAnswer(String answer) {
        this.defaultAnswer = answer;
    }

    /** 当前主回答（供按 Supplier 绑定的桩路由动态读取，用例级改答场景） */
    public String currentDefaultAnswer() {
        return defaultAnswer;
    }

    public void setUsage(int prompt, int completion) {
        this.promptTokens = prompt;
        this.completionTokens = completion;
    }

    public void setResponseRouter(Function<String, String> router) {
        this.responseRouter = router;
    }

    public void setStreamChunkSize(int chunkSize) {
        this.streamChunkSize = chunkSize;
    }

    /** 用例间状态重置（@BeforeEach 调用） */
    public void reset() {
        this.defaultAnswer = DEFAULT_ANSWER;
        this.promptTokens = 100;
        this.completionTokens = 50;
        this.responseRouter = null;
        this.streamChunkSize = 10;
        this.userTexts.clear();
        this.promptSnapshots.clear();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String answer = recordAndRoute(prompt);
        return buildResponse(answer, true);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        String answer = recordAndRoute(prompt);
        List<String> chunks = splitIntoChunks(answer, streamChunkSize);
        return Flux.fromIterable(chunks)
            .map(chunk -> buildResponse(chunk, chunk.equals(chunks.get(chunks.size() - 1))));
    }

    private String recordAndRoute(Prompt prompt) {
        String userText = prompt.getUserMessage().getText();
        userTexts.add(userText == null ? "" : userText);
        promptSnapshots.add(prompt.getInstructions().stream()
            .map(m -> m.getText() == null ? "" : m.getText())
            .toList());
        Function<String, String> router = this.responseRouter;
        return router != null ? router.apply(userText) : defaultAnswer;
    }

    private ChatResponse buildResponse(String text, boolean includeUsage) {
        DefaultUsage usage = includeUsage
            ? new DefaultUsage(promptTokens, completionTokens)
            : new DefaultUsage(0, 0);
        return new ChatResponse(
            List.of(new Generation(new AssistantMessage(text))),
            ChatResponseMetadata.builder()
                .usage(usage)
                .model("stub-model")
                .build());
    }

    private static List<String> splitIntoChunks(String text, int chunkSize) {
        if (text == null || text.isEmpty()) {
            return List.of("");
        }
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += chunkSize) {
            chunks.add(text.substring(i, Math.min(i + chunkSize, text.length())));
        }
        return chunks;
    }
}
