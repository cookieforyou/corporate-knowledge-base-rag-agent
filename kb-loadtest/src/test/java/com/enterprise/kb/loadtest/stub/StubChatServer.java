package com.enterprise.kb.loadtest.stub;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAI 兼容 SSE 生成桩（簇⑥ 批5 v2.59，场景 B / D-桩形态）
 *
 * <p><b>职责</b>：压测期替代主模型端点（kb-api {@code DEEPSEEK_BASE_URL} 指向本服务），
 * 对应用层并发模型（虚拟线程 / SSE 转发 / 护栏 Advisor 链 / Redis 配额 / 双路检索）施加
 * 真实压力而**不消耗真实 LLM token**。契约对齐 OpenAI {@code chat.completion.chunk} 形态
 * （Spring AI OpenAI 兼容客户端消费，SmartRoutingChatModel 主链路）：
 * delta chunks → finish_reason stop → usage chunk（{@code stream_options.include_usage}
 * 契约，流式计账消费）→ {@code data: [DONE]}。
 *
 * <p><b>纯 JDK 实现</b>（com.sun.net.httpserver + 虚拟线程执行器），零第三方依赖，
 * 支持源码单文件启动（ECS 宿主侧）：
 * <pre>java kb-loadtest/src/test/java/com/enterprise/kb/loadtest/stub/StubChatServer.java --port 9988</pre>
 *
 * <p>参数（CLI 或 env，CLI 优先）：{@code --port}（STUB_PORT，缺省 9988）/
 * {@code --tokens}（STUB_TOKENS，缺省 40）/ {@code --interval}（STUB_INTERVAL_MS，缺省 10）/
 * {@code --first-delay}（STUB_FIRST_DELAY_MS，缺省 50）。
 */
public final class StubChatServer {

    private static final Pattern MODEL_PATTERN = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern STREAM_PATTERN = Pattern.compile("\"stream\"\\s*:\\s*true");
    /** 应答分片词表（中性语料，无业务语义，仅撑流式帧序列） */
    private static final String[] TOKEN_PARTS = {"这是", "压测桩", "生成的", "应答", "分片。"};
    private static final AtomicLong REQUEST_COUNTER = new AtomicLong();

    private final int tokenCount;
    private final long intervalMs;
    private final long firstDelayMs;

    private StubChatServer(int tokenCount, long intervalMs, long firstDelayMs) {
        this.tokenCount = tokenCount;
        this.intervalMs = intervalMs;
        this.firstDelayMs = firstDelayMs;
    }

    /**
     * 启动桩服务。port=0 为系统分配（测试用）；返回已启动实例，调用方负责 stop。
     */
    public static HttpServer start(int port, int tokenCount, long intervalMs, long firstDelayMs)
            throws IOException {
        StubChatServer stub = new StubChatServer(tokenCount, intervalMs, firstDelayMs);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/chat/completions", stub::completions);
        server.createContext("/v1/chat/completions", stub::completions);
        server.createContext("/health", exchange -> respond(exchange, 200, "text/plain", "OK"));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    private void completions(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain", "method not allowed");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        long sequence = REQUEST_COUNTER.incrementAndGet();
        String model = extractModel(body);
        if (!STREAM_PATTERN.matcher(body).find()) {
            respond(exchange, 200, "application/json", nonStreamCompletion(sequence, model));
            log("seq=" + sequence + " model=" + model + " mode=non-stream");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            sleepQuietly(firstDelayMs);
            String id = "chatcmpl-stub-" + sequence;
            long created = Instant.now().getEpochSecond();
            for (int i = 0; i < tokenCount; i++) {
                String content = TOKEN_PARTS[i % TOKEN_PARTS.length] + (i + 1) + " ";
                String delta = i == 0
                    ? "{\"role\":\"assistant\",\"content\":" + jsonString(content) + "}"
                    : "{\"content\":" + jsonString(content) + "}";
                writeSse(out, chunk(id, created, model, delta, null));
                sleepQuietly(intervalMs);
            }
            writeSse(out, chunk(id, created, model, "{}", "stop"));
            writeSse(out, usageChunk(id, created, model));
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        log("seq=" + sequence + " model=" + model + " tokens=" + tokenCount);
    }

    /** delta/终止 chunk（OpenAI chat.completion.chunk 形态） */
    private String chunk(String id, long created, String model, String deltaJson, String finishReason) {
        return "{\"id\":" + jsonString(id)
            + ",\"object\":\"chat.completion.chunk\""
            + ",\"created\":" + created
            + ",\"model\":" + jsonString(model)
            + ",\"choices\":[{\"index\":0,\"delta\":" + deltaJson
            + ",\"finish_reason\":" + (finishReason == null ? "null" : jsonString(finishReason)) + "}]}";
    }

    /** usage chunk（stream_options.include_usage 契约：choices 空 + usage 尾随） */
    private String usageChunk(String id, long created, String model) {
        int promptTokens = 12;
        return "{\"id\":" + jsonString(id)
            + ",\"object\":\"chat.completion.chunk\""
            + ",\"created\":" + created
            + ",\"model\":" + jsonString(model)
            + ",\"choices\":[]"
            + ",\"usage\":{\"prompt_tokens\":" + promptTokens
            + ",\"completion_tokens\":" + tokenCount
            + ",\"total_tokens\":" + (promptTokens + tokenCount) + "}}";
    }

    /** 非流式兜底（防御：Spring AI call() 形态误入桩时仍可应答） */
    private String nonStreamCompletion(long sequence, String model) {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < tokenCount; i++) {
            content.append(TOKEN_PARTS[i % TOKEN_PARTS.length]).append(i + 1).append(' ');
        }
        return "{\"id\":" + jsonString("chatcmpl-stub-" + sequence)
            + ",\"object\":\"chat.completion\""
            + ",\"created\":" + Instant.now().getEpochSecond()
            + ",\"model\":" + jsonString(model)
            + ",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":"
            + jsonString(content.toString().trim()) + "},\"finish_reason\":\"stop\"}]"
            + ",\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":" + tokenCount
            + ",\"total_tokens\":" + (12 + tokenCount) + "}}";
    }

    private static String extractModel(String body) {
        Matcher matcher = MODEL_PATTERN.matcher(body);
        return matcher.find() ? matcher.group(1) : "stub-model";
    }

    private static void writeSse(OutputStream out, String json) throws IOException {
        out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.append('"').toString();
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void log(String line) {
        System.out.println("[StubChatServer] " + Instant.now() + " " + line);
    }

    public static void main(String[] args) throws IOException {
        int port = argInt(args, "--port", "STUB_PORT", 9988);
        int tokens = argInt(args, "--tokens", "STUB_TOKENS", 40);
        long interval = argInt(args, "--interval", "STUB_INTERVAL_MS", 10);
        long firstDelay = argInt(args, "--first-delay", "STUB_FIRST_DELAY_MS", 50);
        HttpServer server = start(port, tokens, interval, firstDelay);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(1)));
        System.out.println("[StubChatServer] listening on :" + server.getAddress().getPort()
            + " tokens=" + tokens + " intervalMs=" + interval + " firstDelayMs=" + firstDelay);
    }

    private static int argInt(String[] args, String flag, String envName, int defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                return Integer.parseInt(args[i + 1]);
            }
        }
        String env = System.getenv(envName);
        return env == null || env.isBlank() ? defaultValue : Integer.parseInt(env.trim());
    }
}
