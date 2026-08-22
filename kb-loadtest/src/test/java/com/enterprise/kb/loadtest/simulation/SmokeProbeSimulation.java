package com.enterprise.kb.loadtest.simulation;

import com.enterprise.kb.loadtest.ChatProtocol;
import com.enterprise.kb.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.asLongAs;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonFile;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.sse;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * 连通性探针（ECS 首跑件）：1 虚拟用户走通 健康检查 → 检索调试 → SSE 全链
 * （首 token → 帧判别 → DONE 收口），验证 JWT、协议解析与帧形态后，
 * 再依序执行 A-D 正式场景。
 *
 * <pre>
 * mvn gatling:test -pl kb-loadtest \
 *   -Dgatling.simulationClass=com.enterprise.kb.loadtest.simulation.SmokeProbeSimulation \
 *   -Dloadtest.baseUrl=http://ECS:8090 -Dloadtest.jwt=...
 * </pre>
 */
public class SmokeProbeSimulation extends Simulation {

    private final HttpProtocolBuilder protocol = LoadTestConfig.authenticatedProtocol();

    private final ScenarioBuilder smokeProbe = scenario("smoke-probe")
        .exec(http("smoke-health").get("/actuator/health").check(status().is(200)))
        .feed(jsonFile(LoadTestConfig.QUERY_FEEDER).circular())
        .exec(http("smoke-retrieval")
            .post(ChatProtocol.RETRIEVAL_SEARCH_PATH)
            .body(ChatProtocol.retrievalBody())
            .asJson()
            .check(status().is(200), jsonPath("$.data").exists()))
        .exec(ChatProtocol.resetInspection("smoke"))
        .exec(sse("smoke-chat")
            .post(ChatProtocol.CHAT_STREAM_PATH)
            .body(ChatProtocol.chatBody())
            .asJson()
            .await(LoadTestConfig.awaitSeconds()).on(ChatProtocol.firstTokenCheck()))
        .asLongAs(ChatProtocol.drainCondition("smoke", LoadTestConfig.awaitSeconds() * 1000L)).on(
            ChatProtocol.drainAndInspect("smoke"),
            pause(Duration.ofMillis(200)))
        .exec(ChatProtocol.requireCleanCompletion("smoke"))
        .exec(session -> {
            boolean trace = session.contains("smokeTrace") && session.getBoolean("smokeTrace");
            long tokens = session.contains("smokeTokens") ? session.getLong("smokeTokens") : 0L;
            System.out.println("[SmokeProbe] SSE 全链走通：tokens=" + tokens
                + (trace ? "，TRACE 溯源帧已收" : "，无 TRACE 帧（免检索直答路径属正常分支）"));
            return session;
        })
        .exec(sse("smoke-chat").close());

    {
        setUp(smokeProbe.injectOpen(atOnceUsers(1)))
            .protocols(protocol)
            .assertions(details("smoke-chat").failedRequests().count().is(0L));
    }
}
