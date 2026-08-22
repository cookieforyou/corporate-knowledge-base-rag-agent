package com.enterprise.kb.loadtest.simulation;

import com.enterprise.kb.loadtest.ChatProtocol;
import com.enterprise.kb.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.jsonFile;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.sse;

/**
 * 场景 B：生成桩压（簇⑥ 4.12：50 并发无 OOM/线程耗尽）
 *
 * <p>前置（ECS）：{@code StubChatServer} 宿主侧启动 + kb-api {@code DEEPSEEK_BASE_URL}
 * 指向桩（容器经 host.docker.internal）+ 对话限流/Token 预算调额或停用
 * （缺省 60 次/60s/租户必被打穿，步骤见用户侧清单 LT1）+ 重启。
 *
 * <p>closed 注入模型维持 50 并发虚拟用户持续对话（每轮 = SSE 连接 → DONE 收口 →
 * 帧判别 → 关闭），真实检索链 + 护栏链 + Redis 配额全程在路，仅生成段由桩承接。
 * 请求名 B-chat-stream 的 responseTime = 连接 → DONE 全对话时延。
 *
 * <pre>
 * mvn gatling:test -pl kb-loadtest \
 *   -Dgatling.simulationClass=com.enterprise.kb.loadtest.simulation.StubGenLoadSimulation \
 *   -Dloadtest.baseUrl=http://ECS:8090 -Dloadtest.jwt=...
 * </pre>
 */
public class StubGenLoadSimulation extends Simulation {

    private final HttpProtocolBuilder protocol = LoadTestConfig.authenticatedProtocol();

    private final ScenarioBuilder stubGenLoad = scenario("B-stub-gen-load")
        .feed(jsonFile(LoadTestConfig.QUERY_FEEDER).circular())
        .exec(ChatProtocol.resetInspection("b"))
        .exec(sse("B-chat-stream")
            .post(ChatProtocol.CHAT_STREAM_PATH)
            .body(ChatProtocol.chatBody())
            .asJson()
            .await(LoadTestConfig.awaitSeconds()).on(ChatProtocol.doneCheck()))
        .exec(ChatProtocol.drainAndInspect("b"))
        .exec(ChatProtocol.requireCleanCompletion("b"))
        .exec(sse("B-chat-stream").close());

    {
        setUp(stubGenLoad.injectClosed(
                rampConcurrentUsers(0).to(LoadTestConfig.bUsers()).during(Duration.ofSeconds(15)),
                constantConcurrentUsers(LoadTestConfig.bUsers())
                    .during(Duration.ofSeconds(LoadTestConfig.bHoldSeconds()))))
            .protocols(protocol)
            .assertions(details("B-chat-stream").failedRequests().count().is(0L));
    }
}
