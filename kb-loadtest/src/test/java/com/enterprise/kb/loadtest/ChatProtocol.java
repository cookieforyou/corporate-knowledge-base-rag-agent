package com.enterprise.kb.loadtest;

import io.gatling.http.action.sse.SseInboundMessage;
import io.gatling.javaapi.core.ActionBuilder;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Body;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.http.SseMessageCheck;

import java.util.List;
import java.util.function.Function;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jmesPath;
import static io.gatling.javaapi.http.HttpDsl.sse;

/**
 * 对话 SSE 协议常量与 Gatling 公共组件（簇⑥ 批5，v2.59）
 *
 * <p><b>帧形态</b>（AgentController + AgentStreamEvent，11.3/3.17）：TOKEN/ERROR/DONE
 * 无名事件、TRACE 命名事件；DONE 帧 3.17 起 JSON 载荷 {messageId, traceId}，错误路径不发 DONE。
 *
 * <p><b>Gatling SSE 消息形态</b>（3.15.1 ServerSentEvent.asJsonString 源码核验）：
 * 无名帧 {@code {"data":{...}}}（data 为 JSON 时原样内嵌），命名帧
 * {@code {"event":"TRACE","data":{...}}}——匹配条件经 jmesPath 直入 data.* 即可。
 *
 * <p><b>帧判别</b>（drainAndInspect）基于 record 声明序的确定性序列化形态
 * TokenEvent(token) / ErrorEvent(error) / DoneEvent(messageId, traceId)，
 * 以结构化键位判别，避免 TRACE 溯源 snippet 文本含同名字面量造成误判。
 */
public final class ChatProtocol {

    public static final String CHAT_STREAM_PATH = "/api/v1/chat/stream";
    public static final String RETRIEVAL_SEARCH_PATH = "/api/v1/retrieval/search";

    private static final String TOKEN_FRAME_PREFIX = "{\"data\":{\"token\":";
    private static final String ERROR_FRAME_MARKER = "\"data\":{\"error\":";
    private static final String DONE_FRAME_MARKER = "\"messageId\":";
    private static final String TRACE_FRAME_MARKER = "\"event\":\"TRACE\"";

    private ChatProtocol() {
    }

    /** 首 token 帧匹配（TTFT 口径锚点）：无名 TOKEN 帧 data.token 存在 */
    public static SseMessageCheck firstTokenCheck() {
        return sse.checkMessage("first-token").matching(jmesPath("data.token").exists());
    }

    /** 终帧匹配：DONE 帧 JSON 载荷 messageId + traceId 均在（3.17 契约） */
    public static SseMessageCheck doneCheck() {
        return sse.checkMessage("done")
            .matching(jmesPath("data.messageId").exists(), jmesPath("data.traceId").exists());
    }

    /**
     * chat 请求体（函数形态）：feeder 语料经 JSON 转义后装配，规避 EL 插值的引号/注入问题；
     * 会话属性 {@code dSessionId} 存在时附带（场景 D 多轮记忆复用）。
     */
    public static Body.WithString chatBody() {
        return StringBody(session -> {
            StringBuilder body = new StringBuilder("{\"query\":\"")
                .append(escapeJson(session.getString("question")))
                .append("\",\"mode\":\"rag\"");
            if (session.contains("dSessionId")) {
                body.append(",\"sessionId\":\"").append(session.getString("dSessionId")).append("\"");
            }
            return body.append('}').toString();
        });
    }

    /** 检索调试请求体（函数形态，同 chatBody 转义纪律） */
    public static Body.WithString retrievalBody() {
        return StringBody(session -> "{\"query\":\"" + escapeJson(session.getString("question")) + "\"}");
    }

    /** 每轮对话前重置判别状态（closed 注入模型会话跨迭代复用，防上一轮 DONE 残留短路本轮等待） */
    public static ChainBuilder resetInspection(String prefix) {
        return exec(session -> session
            .set(prefix + "Tokens", 0L)
            .set(prefix + "FirstTs", 0L)
            .set(prefix + "LastTs", 0L)
            .set(prefix + "Error", false)
            .set(prefix + "Done", false)
            .set(prefix + "Trace", false)
            .set(prefix + "Start", System.currentTimeMillis()));
    }

    /**
     * drain 循环终止条件：未 DONE ∧ 无 ERROR ∧ 在安全窗口内。
     * 安全窗口兜底半挂流（连接存活但停帧不发）——无此条件时 drain 循环无限自旋。
     */
    public static Function<Session, Boolean> drainCondition(String prefix, long windowMillis) {
        return session -> {
            boolean done = session.contains(prefix + "Done") && session.getBoolean(prefix + "Done");
            boolean error = session.contains(prefix + "Error") && session.getBoolean(prefix + "Error");
            long start = session.contains(prefix + "Start") ? session.getLong(prefix + "Start") : 0L;
            boolean withinWindow = start > 0L
                && System.currentTimeMillis() - start < windowMillis;
            return !done && !error && withinWindow;
        };
    }

    /**
     * 汲干未匹配消息缓冲并判别帧：写入 {prefix}Tokens/FirstTs/LastTs/Error/Done/Trace。
     * 单次调用汲干当前全部缓冲；await DONE 匹配后调用一次即可收拢 TOKEN/TRACE 帧。
     */
    public static ActionBuilder drainAndInspect(String prefix) {
        return sse.processUnmatchedMessages((List<SseInboundMessage> messages, Session session) -> {
            long tokens = session.contains(prefix + "Tokens") ? session.getLong(prefix + "Tokens") : 0L;
            long firstTs = session.contains(prefix + "FirstTs") ? session.getLong(prefix + "FirstTs") : 0L;
            long lastTs = session.contains(prefix + "LastTs") ? session.getLong(prefix + "LastTs") : 0L;
            boolean error = session.contains(prefix + "Error") && session.getBoolean(prefix + "Error");
            boolean done = session.contains(prefix + "Done") && session.getBoolean(prefix + "Done");
            boolean trace = session.contains(prefix + "Trace") && session.getBoolean(prefix + "Trace");
            for (SseInboundMessage message : messages) {
                String raw = message.message();
                if (raw.startsWith(TOKEN_FRAME_PREFIX)) {
                    tokens++;
                    if (firstTs == 0L) {
                        firstTs = message.timestamp();
                    }
                    lastTs = message.timestamp();
                } else if (raw.contains(ERROR_FRAME_MARKER)) {
                    error = true;
                } else if (raw.contains(DONE_FRAME_MARKER)) {
                    done = true;
                } else if (raw.contains(TRACE_FRAME_MARKER)) {
                    trace = true;
                }
            }
            return session
                .set(prefix + "Tokens", tokens)
                .set(prefix + "FirstTs", firstTs)
                .set(prefix + "LastTs", lastTs)
                .set(prefix + "Error", error)
                .set(prefix + "Done", done)
                .set(prefix + "Trace", trace);
        });
    }

    /** 对话收口断言：必须 DONE 收口且零 ERROR 帧（违例抛出 → 该虚拟用户迭代记失败） */
    public static ChainBuilder requireCleanCompletion(String prefix) {
        return exec(session -> {
            boolean error = session.contains(prefix + "Error") && session.getBoolean(prefix + "Error");
            boolean done = session.contains(prefix + "Done") && session.getBoolean(prefix + "Done");
            if (error) {
                throw new IllegalStateException("本轮对话收到 ERROR 帧（压测口径要求零错误帧）");
            }
            if (!done) {
                throw new IllegalStateException("本轮对话未以 DONE 帧正常收口");
            }
            return session;
        });
    }

    /** JSON 字符串最小转义（feeder 语料生成期已过滤特殊字符，此处防御性兜底） */
    public static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
