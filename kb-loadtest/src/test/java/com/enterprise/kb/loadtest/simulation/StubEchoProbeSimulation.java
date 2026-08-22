package com.enterprise.kb.loadtest.simulation;

import com.enterprise.kb.loadtest.ChatProtocol;
import com.enterprise.kb.loadtest.LoadTestConfig;
import io.gatling.http.action.sse.SseInboundMessage;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;
import java.util.List;

import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.asLongAs;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.core.CoreDsl.jmesPath;
import static io.gatling.javaapi.http.HttpDsl.sse;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * 桩上探针（机器侧验证件）：Gatling SSE 解析通路对 {@code StubChatServer} 直压，
 * 不经 kb-api、无需 JWT——验证 SSE DSL 用法、OpenAI chunk 解析与 [DONE] 收束。
 * {@code data: [DONE]} 非 JSON（Gatling 消息序列化后为非法 JSON 文本），判别走
 * processUnmatchedMessages 原文扫描而非 jmesPath（对齐官方 LLM API 指南形态）。
 *
 * <pre>
 * # 先起桩：java kb-loadtest/src/test/java/com/enterprise/kb/loadtest/stub/StubChatServer.java --port 9988
 * mvn gatling:test -pl kb-loadtest \
 *   -Dgatling.simulationClass=com.enterprise.kb.loadtest.simulation.StubEchoProbeSimulation \
 *   -Dloadtest.baseUrl=http://localhost:9988
 * </pre>
 */
public class StubEchoProbeSimulation extends Simulation {

    private final HttpProtocolBuilder protocol = http.baseUrl(LoadTestConfig.baseUrl())
        .sseUnmatchedInboundMessageBufferSize(1_000);

    private final ScenarioBuilder stubEcho = scenario("stub-echo-probe")
        .exec(http("stub-health").get("/health").check(status().is(200)))
        .exec(ChatProtocol.resetInspection("stub"))
        .exec(sse("stub-stream")
            .post("/chat/completions")
            .body(StringBody("{\"model\":\"stub-model\",\"stream\":true,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"连通性探针\"}]}"))
            .asJson()
            .await(30).on(sse.checkMessage("first-delta")
                .matching(jmesPath("data.choices[0].delta.content").exists())))
        .asLongAs(ChatProtocol.drainCondition("stub", 60_000L)).on(
            sse.processUnmatchedMessages((List<SseInboundMessage> messages,
                                          io.gatling.javaapi.core.Session session) -> {
                boolean done = session.contains("stubDone") && session.getBoolean("stubDone");
                for (SseInboundMessage message : messages) {
                    if (message.message().contains("[DONE]")) {
                        done = true;
                    }
                }
                return session.set("stubDone", done);
            }),
            pause(Duration.ofMillis(100)))
        .exec(session -> {
            if (!session.contains("stubDone") || !session.getBoolean("stubDone")) {
                throw new IllegalStateException("桩流未以 data: [DONE] 收束");
            }
            return session;
        })
        .exec(sse("stub-stream").close());

    {
        setUp(stubEcho.injectOpen(atOnceUsers(1)))
            .protocols(protocol)
            .assertions(details("stub-stream").failedRequests().count().is(0L));
    }
}
