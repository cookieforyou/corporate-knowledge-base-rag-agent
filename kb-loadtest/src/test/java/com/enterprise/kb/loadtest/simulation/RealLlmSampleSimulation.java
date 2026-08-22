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
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.jsonFile;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.repeat;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.sse;

/**
 * 场景 C：真实 LLM 小样本采样（簇⑥ 4.12 验收线：TTFT P95 &lt; 2s、TPOT &lt; 100ms）
 *
 * <p><b>计费敏感</b>：直压真实主模型（DeepSeek），缺省关闭——构造期即拒绝运行，
 * 须 {@code -Dloadtest.c.enabled=true} 显式确认（用户侧执行前确认口径）。
 *
 * <p>采样形态：1 虚拟用户串行 N 条（缺省 10，n 小为抽样体检非统计基准）。
 * TTFT = 请求 C-ttft 的 responseTime（POST → 首 TOKEN 帧，Gatling await 口径，
 * 与服务端 rag.ttft 指标同源不同层——后者自 Controller 入口计时）；
 * TPOT = drain 阶段 TOKEN 帧到达时间戳差分均值（帧数 &gt; 1 时），
 * 超阈即本轮判失败并输出实测值供报告回填。
 *
 * <pre>
 * mvn gatling:test -pl kb-loadtest \
 *   -Dgatling.simulationClass=com.enterprise.kb.loadtest.simulation.RealLlmSampleSimulation \
 *   -Dloadtest.baseUrl=http://ECS:8090 -Dloadtest.jwt=... -Dloadtest.c.enabled=true
 * </pre>
 */
public class RealLlmSampleSimulation extends Simulation {

    private final HttpProtocolBuilder protocol = LoadTestConfig.authenticatedProtocol();

    private final ScenarioBuilder realLlmSample = scenario("C-real-llm-sample")
        .repeat(LoadTestConfig.cSamples()).on(
            feed(jsonFile(LoadTestConfig.QUERY_FEEDER).circular())
                .exec(ChatProtocol.resetInspection("c"))
                .exec(sse("C-ttft")
                    .post(ChatProtocol.CHAT_STREAM_PATH)
                    .body(ChatProtocol.chatBody())
                    .asJson()
                    .await(LoadTestConfig.cTtftTimeoutSeconds()).on(ChatProtocol.firstTokenCheck()))
                .asLongAs(ChatProtocol.drainCondition("c", LoadTestConfig.awaitSeconds() * 1000L)).on(
                    ChatProtocol.drainAndInspect("c"),
                    pause(Duration.ofMillis(200)))
                .exec(ChatProtocol.requireCleanCompletion("c"))
                .exec(session -> {
                    long tokens = session.contains("cTokens") ? session.getLong("cTokens") : 0L;
                    long firstTs = session.contains("cFirstTs") ? session.getLong("cFirstTs") : 0L;
                    long lastTs = session.contains("cLastTs") ? session.getLong("cLastTs") : 0L;
                    double tpotMs = tokens > 1 ? (double) (lastTs - firstTs) / (tokens - 1) : 0.0;
                    System.out.printf("[ScenarioC] 样本完成：tokens=%d TPOT=%.1fms%n", tokens, tpotMs);
                    if (tokens > 1 && tpotMs > LoadTestConfig.cTpotThresholdMs()) {
                        throw new IllegalStateException(String.format(
                            "TPOT %.1fms 超阈值 %dms", tpotMs, LoadTestConfig.cTpotThresholdMs()));
                    }
                    return session;
                })
                .exec(sse("C-ttft").close()));

    {
        if (!LoadTestConfig.cEnabled()) {
            throw new IllegalStateException(
                "场景 C 消耗真实 LLM token（计费敏感）：确认后以 -Dloadtest.c.enabled=true 显式运行");
        }
        setUp(realLlmSample.injectOpen(atOnceUsers(1)))
            .protocols(protocol)
            .assertions(details("C-ttft").responseTime().percentile3()
                .lt(LoadTestConfig.cTtftThresholdMs()));
    }
}
