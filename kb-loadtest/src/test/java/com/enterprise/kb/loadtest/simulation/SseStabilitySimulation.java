package com.enterprise.kb.loadtest.simulation;

import com.enterprise.kb.loadtest.ChatProtocol;
import com.enterprise.kb.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;
import java.util.UUID;

import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.doIf;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonFile;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.sse;

/**
 * 场景 D：20 并发 SSE 长会话稳定性（簇⑥ 4.12：零中断 + 全 DONE 帧）
 *
 * <p>closed 注入模型维持 20 并发虚拟用户，持续窗口内每轮 = 一次完整 SSE 对话；
 * 每用户首轮生成 sessionId 后**全程复用**——多轮记忆链（Redis ChatMemory +
 * reseed 回填 + PG 归档旁路）在并发下真跑。断言：全对话零失败
 * （await DONE 超时/ERROR 帧/无 DONE 收口均记失败）。
 *
 * <p>前置二选一（ECS，见用户侧清单 LT1）：① 桩形态（同场景 B 前置，成本零，
 * 推荐首跑）；② 真实主模型形态（消耗 token，限流/预算须先调额）。
 *
 * <pre>
 * mvn gatling:test -pl kb-loadtest \
 *   -Dgatling.simulationClass=com.enterprise.kb.loadtest.simulation.SseStabilitySimulation \
 *   -Dloadtest.baseUrl=http://ECS:8090 -Dloadtest.jwt=...
 * </pre>
 */
public class SseStabilitySimulation extends Simulation {

    private final HttpProtocolBuilder protocol = LoadTestConfig.authenticatedProtocol();

    private final ScenarioBuilder sseStability = scenario("D-sse-stability")
        .doIf(session -> !session.contains("dSessionId")).then(
            exec(session -> session.set("dSessionId", UUID.randomUUID().toString())))
        .feed(jsonFile(LoadTestConfig.QUERY_FEEDER).circular())
        .exec(ChatProtocol.resetInspection("d"))
        .exec(sse("D-chat-round")
            .post(ChatProtocol.CHAT_STREAM_PATH)
            .body(ChatProtocol.chatBody())
            .asJson()
            .await(LoadTestConfig.awaitSeconds()).on(ChatProtocol.doneCheck()))
        .exec(ChatProtocol.drainAndInspect("d"))
        .exec(ChatProtocol.requireCleanCompletion("d"))
        .exec(sse("D-chat-round").close());

    {
        setUp(sseStability.injectClosed(
                rampConcurrentUsers(0).to(LoadTestConfig.dUsers()).during(Duration.ofSeconds(10)),
                constantConcurrentUsers(LoadTestConfig.dUsers())
                    .during(Duration.ofSeconds(LoadTestConfig.dDurationSeconds()))))
            .protocols(protocol)
            .assertions(details("D-chat-round").failedRequests().count().is(0L));
    }
}
