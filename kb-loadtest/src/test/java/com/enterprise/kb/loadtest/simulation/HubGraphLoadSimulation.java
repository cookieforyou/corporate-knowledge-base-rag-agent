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
 * 场景 E：hub 图路压测（压测资产第 5 场景；图路 = Phase5簇④ GraphRAG）
 *
 * <p><b>① 场景意图</b>：压图路 1 跳展开在<b>高连接度 hub 实体</b>下的延迟与稳定性。
 * 语料问题刻意命中 hub 实体（被大量文档共同提及的实体，如缺省样例的「示例实体」）：
 * 种子匹配阶段实体向量索引近邻密集命中，1 跳展开阶段单种子邻域逼近图内最大度数，
 * 于是邻域收集与候选封顶路径（种子封顶 → 1 跳展开 → 候选按 hop/贡献/id 全序截断
 * {@code candidate-limit} 且种子恒在 → chunk 反查 + 租户纵深）成为延迟主导项。
 * 场景 A 的语料多为低度实体，图路开销被双路（向量 + BM25）掩盖，本场景即补这一盲区。
 *
 * <p><b>② 前置</b>（三条同时成立，否则读数不具场景 E 意义）：
 * <ol>
 *   <li>{@code rag.graph.enabled=true}——关闭态图路条件装配整体缺位，本压测退化为双路检索；</li>
 *   <li>图内存在<b>高连接度实体</b>（种子实体在租户图内度数居前）：度数是本场景唯一自变量——
 *       ① 度数高于 {@code rag.graph.retrieval.candidate-limit}（缺省 100）才进入候选全序截断
 *       （hop/贡献/中心度/id，种子恒在）；② 度数高于
 *       {@code rag.graph.retrieval.neighbor-limit}（缺省 256，v3.04 单种子邻居采样上限）才真正
 *       压到按度采样路径（该上限按 {@code mention_count} 降序取，仅约束中间结果规模；
 *       展开遍历的扫描代价仍与度数成正比）。低度实体几乎不触发，读数与场景 A 无异。
 *       度数查法（租户 = JWT 的 owner claim）：
 *       <pre>
 * MATCH (e:Entity {tenant_id: '&lt;租户&gt;'})
 * OPTIONAL MATCH (e)-[r:RELATED_TO]-()
 * RETURN e.name AS name, count(r) AS oneHopDegree,
 *        size(coalesce(e.chunk_ids, [])) AS mentionedChunks
 * ORDER BY oneHopDegree DESC LIMIT 5
 *       </pre>
 *       </li>
 *   <li>feeder {@code loadtest-hub-queries.json} 由用户从自有语料生成：把若干条<b>命名该 hub 实体</b>
 *       的问题写进本文件（覆盖仓库内样例；格式同 {@code loadtest-queries.json} 的
 *       id/category/question），经 {@link LoadTestConfig#HUB_QUERY_FEEDER} circular 循环消费。
 *       仓库内 3 条占位样例仅供跑通链路，<b>不代表 hub 语义</b>。</li>
 * </ol>
 *
 * <p><b>③ 运行</b>：
 * <pre>
 * mvn gatling:test -pl kb-loadtest \
 *   -Dgatling.simulationClass=com.enterprise.kb.loadtest.simulation.HubGraphLoadSimulation \
 *   -Dloadtest.baseUrl=http://ECS:8090 -Dloadtest.jwt=...
 * </pre>
 * 注入速率/时长/阈值分别经 {@code -Dloadtest.e.rate}（缺省 2 用户/秒）、
 * {@code -Dloadtest.e.duration.seconds}（缺省 60s）、{@code -Dloadtest.e.p95.threshold.ms}（缺省 800ms）调节。
 *
 * <p><b>④ 阈值口径</b>：缺省 P95 阈值 800ms 是<b>建议线</b>，非实测结论——取值 = 场景 A
 * 三路融合基线 600ms + hub 展开/候选封顶余量 200ms；须以用户侧 ECS 实测基线复核后回写压测文档，
 * <b>不得</b>在复核前声称该阈值已验证。断言 = P95 &lt; 阈值 + 失败请求数 = 0。
 *
 * <p><b>⑤ 边界</b>：本场景只压检索调试端点（图路检索本身零 LLM：查询嵌入 + 实体匹配 + 展开 + 反查），
 * <b>不触发 LLM 生成</b>（无 {@code /chat/stream} 调用），生成侧计费为零；token 成本仅来自查询嵌入。
 */
public class HubGraphLoadSimulation extends Simulation {

    private final HttpProtocolBuilder protocol = LoadTestConfig.authenticatedProtocol();

    private final ScenarioBuilder hubGraphLoad = scenario("E-hub-retrieval")
        .feed(jsonFile(LoadTestConfig.HUB_QUERY_FEEDER).circular())
        .exec(http("E-hub-retrieval")
            .post(ChatProtocol.RETRIEVAL_SEARCH_PATH)
            .body(ChatProtocol.retrievalBody())
            .asJson()
            .check(status().is(200), jsonPath("$.data.candidates").exists()));

    {
        setUp(hubGraphLoad.injectOpen(
                constantUsersPerSec(LoadTestConfig.eRate())
                    .during(Duration.ofSeconds(LoadTestConfig.eDurationSeconds()))))
            .protocols(protocol)
            .assertions(
                details("E-hub-retrieval").responseTime().percentile3()
                    .lt(LoadTestConfig.eP95ThresholdMs()),
                details("E-hub-retrieval").failedRequests().count().is(0L));
    }
}
