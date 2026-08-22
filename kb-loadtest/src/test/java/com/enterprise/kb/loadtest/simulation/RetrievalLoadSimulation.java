package com.enterprise.kb.loadtest.simulation;

import com.enterprise.kb.loadtest.ChatProtocol;
import com.enterprise.kb.loadtest.LoadTestConfig;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.jsonFile;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * 场景 A：检索真压（簇⑥ 4.12 验收线：P95 &lt; 500ms）
 *
 * <p>直压检索调试端点 POST /api/v1/retrieval/search（不经 LLM 生成，
 * 链路 = 查询改写 LLM 调用 + 双路召回（Milvus/ES）+ RRF + rerank 外部调用）。
 * 语料 = Golden 干净集按 ID 抽取（{@code loadtest-queries.json}），circular 循环。
 *
 * <p>检索链无租户限流桶（RateLimitAdvisor 仅挂对话 advisor 链），无需调额前置。
 * 响应体自带 {@code data.latencyMs} 分段（rewrite/retrieval/rerank/total），
 * 报告可对照客户端 P95 做服务端分段归因。
 *
 * <pre>
 * mvn gatling:test -pl kb-loadtest \
 *   -Dgatling.simulationClass=com.enterprise.kb.loadtest.simulation.RetrievalLoadSimulation \
 *   -Dloadtest.baseUrl=http://ECS:8090 -Dloadtest.jwt=...
 * </pre>
 */
public class RetrievalLoadSimulation extends Simulation {

    private final HttpProtocolBuilder protocol = LoadTestConfig.authenticatedProtocol();

    private final ScenarioBuilder retrievalLoad = scenario("A-retrieval-load")
        .feed(jsonFile(LoadTestConfig.QUERY_FEEDER).circular())
        .exec(http("A-retrieval-search")
            .post(ChatProtocol.RETRIEVAL_SEARCH_PATH)
            .body(ChatProtocol.retrievalBody())
            .asJson()
            .check(status().is(200), jsonPath("$.data.candidates").exists()));

    {
        setUp(retrievalLoad.injectOpen(
                constantUsersPerSec(LoadTestConfig.aRate())
                    .during(Duration.ofSeconds(LoadTestConfig.aDurationSeconds()))))
            .protocols(protocol)
            .assertions(
                details("A-retrieval-search").responseTime().percentile3()
                    .lt(LoadTestConfig.aP95ThresholdMs()),
                details("A-retrieval-search").failedRequests().count().is(0L));
    }
}
