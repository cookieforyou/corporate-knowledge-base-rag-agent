package com.enterprise.kb.eval.runner;

import com.enterprise.kb.ai.config.GraphRetrievalProperties;
import com.enterprise.kb.ai.config.RetrievalProperties;
import com.enterprise.kb.eval.dataset.GoldenDatasetLoader;
import com.enterprise.kb.eval.dataset.GoldenQAPair;
import com.enterprise.kb.eval.dataset.QACategory;
import com.enterprise.kb.infrastructure.graph.GraphGateway;
import com.enterprise.kb.infrastructure.graph.GraphRecords;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 图路展开方向 A/B 扫描工具单测——假网关 + 假嵌入模型，零网络零真库零 LLM。
 *
 * <p>覆盖四项验收：① 四方向都被扫描（且每问题只嵌入一次）；② 指标计算精确
 * （脚本化命中/未命中 → hit@k / recall@k / MRR / 跳数分布的确定数值）；
 * ③ 报告 JSON 结构可解析（原始 Map 结构 + 记录反序列化双校验）；
 * ④ 缺租户 / 网关缺位 / 图未启用 / 非触发参数时走守卫分支不抛异常且零触达。
 */
class GraphDirectionAbRunnerTest {

    private static final String TENANT = "tenant-ab";
    private static final String ARGS_ON = "--eval.graph-direction-ab";

    private JsonMapper jsonMapper;
    private GraphRetrievalProperties graphProperties;
    private RetrievalProperties retrievalProperties;
    private GoldenDatasetLoader datasetLoader;

    @BeforeEach
    void setUp() {
        jsonMapper = JsonMapper.builder().build();
        graphProperties = new GraphRetrievalProperties();
        retrievalProperties = new RetrievalProperties();
        datasetLoader = mock(GoldenDatasetLoader.class);
        when(datasetLoader.loadAll()).thenReturn(List.of(
            pair("mh-1", QACategory.MULTI_HOP, "q1", List.of("c1", "c2")),
            pair("mh-2", QACategory.MULTI_HOP, "q2", List.of("c3")),
            pair("mh-3", QACategory.MULTI_HOP, "q3", List.of("c4")),
            // 非多跳分类：不参与 chunk 级指标（证明过滤生效——否则嵌入/检索调用数会变 16/4）
            pair("fact-1", QACategory.FACTOID, "q4", List.of("c5")),
            // 多跳但无 expectedChunkIds：chunk 级指标不可算，同样跳过
            pair("mh-4", QACategory.MULTI_HOP, "q5", List.of())));
    }

    // ── ① 四方向都被扫描 + 每条问题只嵌入一次 ──

    @Test
    void scansAllFourDirectionsWithSingleEmbeddingPerQuestion(@TempDir Path dir) {
        RecordingFakeGateway gateway = new RecordingFakeGateway(script());
        CountingEmbeddingModel embeddingModel = new CountingEmbeddingModel();

        run(runner(gateway, embeddingModel, dir.resolve("ab.json")), ARGS_ON);

        assertThat(gateway.specs).hasSize(GraphDirectionAbRunner.SCAN_DIRECTIONS.size() * 3);
        assertThat(gateway.specs.stream().map(GraphRecords.GraphRetrievalSpec::expandDirection).distinct())
            .containsExactlyInAnyOrderElementsOf(GraphDirectionAbRunner.SCAN_DIRECTIONS);
        assertThat(gateway.tenants).containsOnly(TENANT);
        // 3 条多跳题 × 四方向复用同一向量 → 嵌入只调用 3 次（若四方向各嵌一次则为 12）
        assertThat(embeddingModel.embedCalls).isEqualTo(3);
    }

    // ── ② 指标计算精确（确定性脚本 → 具体数值） ──

    @Test
    void metricsAreExactForScriptedHits(@TempDir Path dir) {
        Path report = dir.resolve("ab.json");
        run(runner(new RecordingFakeGateway(script()), new CountingEmbeddingModel(), report), ARGS_ON);

        Map<String, GraphDirectionAbRunner.DirectionReport> byDirection = byDirection(readReport(report));

        GraphDirectionAbRunner.DirectionReport both = byDirection.get("BOTH");
        assertThat(both.questionCount()).isEqualTo(3);
        assertThat(both.hitAtK()).isCloseTo(2.0 / 3.0, within(1e-4));
        assertThat(both.recallAtK()).isCloseTo(0.5, within(1e-9));
        assertThat(both.mrr()).isCloseTo(2.0 / 3.0, within(1e-4));
        assertThat(both.hop0Hits()).isEqualTo(2);
        assertThat(both.hop1PlusHits()).isEqualTo(2);
        assertThat(both.avgHitsPerQuestion()).isCloseTo(4.0 / 3.0, within(1e-4));
        assertThat(both.details()).hasSize(3);
        assertThat(both.details().get(0).hit()).isTrue();
        assertThat(both.details().get(0).recallAtK()).isCloseTo(0.5, within(1e-9));
        assertThat(both.details().get(0).reciprocalRank()).isCloseTo(1.0, within(1e-9));
        assertThat(both.details().get(2).hit()).isFalse();
        assertThat(both.details().get(2).reciprocalRank()).isZero();

        GraphDirectionAbRunner.DirectionReport outgoing = byDirection.get("OUTGOING");
        assertThat(outgoing.hitAtK()).isZero();
        assertThat(outgoing.recallAtK()).isZero();
        assertThat(outgoing.mrr()).isZero();
        assertThat(outgoing.hop0Hits()).isZero();
        assertThat(outgoing.hop1PlusHits()).isZero();

        GraphDirectionAbRunner.DirectionReport incoming = byDirection.get("INCOMING");
        assertThat(incoming.hitAtK()).isEqualTo(1.0);
        assertThat(incoming.recallAtK()).isEqualTo(1.0);
        // 逐题 RR = 1/2, 1/2, 1/1 → 均值 2/3（期望片段不在首位时位次分被正确折减）
        assertThat(incoming.mrr()).isCloseTo(2.0 / 3.0, within(1e-4));
        assertThat(incoming.hop0Hits()).isEqualTo(2);
        assertThat(incoming.hop1PlusHits()).isEqualTo(4);

        GraphDirectionAbRunner.DirectionReport none = byDirection.get("NONE");
        assertThat(none.hitAtK()).isCloseTo(1.0 / 3.0, within(1e-4));
        assertThat(none.recallAtK()).isCloseTo(1.0 / 6.0, within(1e-4));
        assertThat(none.mrr()).isCloseTo(1.0 / 3.0, within(1e-4));
        assertThat(none.hop1PlusHits()).isZero();
    }

    @Test
    void conclusionRecommendsBestDirectionWhenDeltaExceedsNoiseBand(@TempDir Path dir) {
        Path report = dir.resolve("ab.json");
        run(runner(new RecordingFakeGateway(script()), new CountingEmbeddingModel(), report), ARGS_ON);

        GraphDirectionAbRunner.AbReport ab = readReport(report);
        assertThat(ab.ranking()).startsWith("排序（recall@k ↓, MRR ↓）：INCOMING");
        assertThat(ab.conclusion())
            .contains("数据建议：rag.graph.retrieval.expand-direction=INCOMING")
            .contains("Δrecall@k=+0.5000");
    }

    @Test
    void conclusionKeepsBothWhenDifferencesWithinNoiseBand(@TempDir Path dir) {
        // 四方向命中一致（方向不影响召回）→ 噪声带内 → 明确「差异不显著，保持 BOTH」
        Map<GraphRecords.ExpandDirection, Map<Integer, List<GraphRecords.GraphChunkHit>>> flat = new EnumMap<>(GraphRecords.ExpandDirection.class);
        for (GraphRecords.ExpandDirection direction : GraphDirectionAbRunner.SCAN_DIRECTIONS) {
            flat.put(direction, Map.of(1, List.of(hit("c1", 0, 0.9)), 2, List.of(hit("c3", 0, 0.8)), 3, List.of(hit("c4", 1, 0.5))));
        }
        Path report = dir.resolve("ab.json");
        run(runner(new RecordingFakeGateway(flat), new CountingEmbeddingModel(), report), ARGS_ON);

        GraphDirectionAbRunner.AbReport ab = readReport(report);
        assertThat(ab.conclusion())
            .contains("差异不显著，保持 BOTH")
            .contains("四方向读数完全一致");
    }

    // ── ③ 报告 JSON 结构可解析 ──

    @Test
    void reportJsonIsWellFormedAndCarriesPerDirectionMetricsAndDetails(@TempDir Path dir) throws Exception {
        Path report = dir.resolve("nested/ab.json");
        run(runner(new RecordingFakeGateway(script()), new CountingEmbeddingModel(), report), ARGS_ON);

        assertThat(Files.exists(report)).isTrue();
        Map<String, Object> raw = jsonMapper.readValue(Files.readString(report),
            new TypeReference<Map<String, Object>>() {});
        assertThat(raw).containsKeys("tenantId", "questionCount", "recallSize", "noiseBand",
            "baselineDirection", "expandNeighbors", "directionGateInert", "specMapping",
            "avgEmbedMillis", "directions", "ranking", "conclusion");
        assertThat(raw.get("tenantId")).isEqualTo(TENANT);
        assertThat(raw.get("questionCount")).isEqualTo(3);
        // k = 生产图路 recallSize（rag.retrieval.top-k × recall-multiplier = 5 × 2）
        assertThat(raw.get("recallSize")).isEqualTo(10);

        List<Map<String, Object>> directions = asList(raw.get("directions"));
        assertThat(directions).hasSize(4);
        assertThat(directions.stream().map(d -> d.get("direction")))
            .containsExactly("BOTH", "OUTGOING", "INCOMING", "NONE");
        Map<String, Object> firstDirection = directions.get(0);
        assertThat(firstDirection).containsKeys("direction", "questionCount", "hitAtK", "recallAtK", "mrr",
            "avgRetrieveMillis", "hop0Hits", "hop1PlusHits", "avgHitsPerQuestion", "details");
        List<Map<String, Object>> details = asList(firstDirection.get("details"));
        assertThat(details).hasSize(3);
        assertThat(details.get(0)).containsKeys("id", "hit", "recallAtK", "reciprocalRank", "retrievedCount",
            "retrievedChunkIds", "expectedChunkIds", "retrieveMillis", "hop0Hits", "hop1PlusHits");
        assertThat(details.get(0).get("id")).isEqualTo("mh-1");
        assertThat(asStringList(details.get(0).get("expectedChunkIds"))).containsExactly("c1", "c2");

        // 记录反序列化可往返（报告消费方直接读记录类型的结构契约）
        GraphDirectionAbRunner.AbReport ab = readReport(report);
        assertThat(ab.directions()).hasSize(4);
        assertThat(ab.baselineDirection()).isEqualTo("BOTH");
        assertThat(ab.directionGateInert()).isFalse();
        assertThat(ab.specMapping()).containsKey("rag.graph.retrieval.entity-top-n → entityTopN");
        assertThat(ab.directions().get(0).details()).hasSize(3);
    }

    // ── ④ 守卫分支：不抛异常 + 零触达 ──

    @Test
    void guardBranchesDoNotThrowAndNeverTouchGateway(@TempDir Path dir) {
        // 非触发参数
        RecordingFakeGateway g1 = new RecordingFakeGateway(script());
        run(runner(g1, new CountingEmbeddingModel(), dir.resolve("a.json")), "--eval.draft-multihop");
        assertThat(g1.specs).isEmpty();

        // 显式 =false
        RecordingFakeGateway g2 = new RecordingFakeGateway(script());
        run(runner(g2, new CountingEmbeddingModel(), dir.resolve("b.json")), "--eval.graph-direction-ab=false");
        assertThat(g2.specs).isEmpty();

        // rag.graph.enabled=false
        RecordingFakeGateway g3 = new RecordingFakeGateway(script());
        GraphDirectionAbRunner disabled = runner(g3, new CountingEmbeddingModel(), dir.resolve("c.json"));
        ReflectionTestUtils.setField(disabled, "graphEnabled", false);
        assertThatCode(() -> run(disabled, ARGS_ON)).doesNotThrowAnyException();
        assertThat(g3.specs).isEmpty();

        // 缺租户
        RecordingFakeGateway g4 = new RecordingFakeGateway(script());
        GraphDirectionAbRunner noTenant = runner(g4, new CountingEmbeddingModel(), dir.resolve("d.json"));
        ReflectionTestUtils.setField(noTenant, "tenantId", "  ");
        assertThatCode(() -> run(noTenant, ARGS_ON)).doesNotThrowAnyException();
        assertThat(g4.specs).isEmpty();
        assertThat(Files.exists(dir.resolve("d.json"))).isFalse();

        // 网关 Bean 缺位
        CountingEmbeddingModel embedding = new CountingEmbeddingModel();
        GraphDirectionAbRunner noGateway = runner(null, embedding, dir.resolve("e.json"));
        assertThatCode(() -> run(noGateway, ARGS_ON)).doesNotThrowAnyException();
        assertThat(embedding.embedCalls).isZero();

        // EmbeddingModel Bean 缺位
        RecordingFakeGateway g5 = new RecordingFakeGateway(script());
        GraphDirectionAbRunner noEmbedding = runner(g5, null, dir.resolve("f.json"));
        assertThatCode(() -> run(noEmbedding, ARGS_ON)).doesNotThrowAnyException();
        assertThat(g5.specs).isEmpty();

        // 图路检索抛异常（Neo4j 不可达形态）：记录错误但不击穿启动
        GraphGateway failing = mock(GraphGateway.class);
        when(failing.retrieveChunks(anyString(), any())).thenThrow(new IllegalStateException("neo4j down"));
        assertThatCode(() -> run(runner(failing, new CountingEmbeddingModel(), dir.resolve("g.json")), ARGS_ON))
            .doesNotThrowAnyException();
        verify(failing).retrieveChunks(eq(TENANT), any());
    }

    @Test
    void gatewayAbsentMeansProviderReturnsNull(@TempDir Path dir) {
        // ObjectProvider 缺位语义显式化（防未来把守卫改成直接注入后静默失败）
        @SuppressWarnings("unchecked")
        ObjectProvider<GraphGateway> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        GraphDirectionAbRunner runner = new GraphDirectionAbRunner(provider, embeddingProvider(new CountingEmbeddingModel()),
            graphProperties, retrievalProperties, datasetLoader, jsonMapper);
        ReflectionTestUtils.setField(runner, "graphEnabled", true);
        ReflectionTestUtils.setField(runner, "tenantId", TENANT);
        ReflectionTestUtils.setField(runner, "reportPath", dir.resolve("h.json").toString());
        ReflectionTestUtils.setField(runner, "noiseBand", 0.02);

        assertThatCode(() -> run(runner, ARGS_ON)).doesNotThrowAnyException();
        verify(provider).getIfAvailable();
    }

    // ── 生产同源 spec 映射（唯一差异 = 方向为扫描变量） ──

    @Test
    void specMirrorsProductionMapping() {
        graphProperties.setEntityTopN(5);
        graphProperties.setEntityOverFetch(8);
        graphProperties.setEntitySimilarityThreshold(0.7);
        graphProperties.setCandidateLimit(100);
        graphProperties.setNeighborLimit(256);
        GraphDirectionAbRunner runner = runner(new RecordingFakeGateway(script()), new CountingEmbeddingModel(),
            Path.of("target/unused.json"));

        float[] embedding = {1.0f};
        GraphRecords.GraphRetrievalSpec spec = runner.specFor(embedding, GraphRecords.ExpandDirection.OUTGOING, 10);

        assertThat(spec.queryEmbedding()).isSameAs(embedding);
        assertThat(spec.entityTopN()).isEqualTo(5);
        assertThat(spec.entityFetchLimit()).isEqualTo(40);        // 5 × 过取 8
        assertThat(spec.similarityThreshold()).isEqualTo(0.7);
        assertThat(spec.expandDirection()).isEqualTo(GraphRecords.ExpandDirection.OUTGOING);
        assertThat(spec.candidateLimit()).isEqualTo(100);
        assertThat(spec.neighborLimit()).isEqualTo(256);          // 单种子邻域上限（v3.04）
        assertThat(spec.limit()).isEqualTo(10);

        // 过取关闭（=1）与 candidate-limit 低于种子数的兜底（逐条对齐 GraphDocumentRetriever）；
        // neighbor-limit 直传不在此处重算——非正值回落候选上限的规则单点在网关
        // （Neo4jGraphGateway.neighborCap，守卫单测直断），调用方抬高会与网关规则分叉
        graphProperties.setEntityOverFetch(1);
        graphProperties.setCandidateLimit(3);
        graphProperties.setNeighborLimit(0);
        GraphRecords.GraphRetrievalSpec floored = runner.specFor(embedding, GraphRecords.ExpandDirection.NONE, 4);
        assertThat(floored.entityFetchLimit()).isEqualTo(5);
        assertThat(floored.candidateLimit()).isEqualTo(5);
        assertThat(floored.neighborLimit()).as("非正值直传，由网关回落候选上限").isZero();
    }

    /**
     * 工具模式登记纪律（v3.05）：EvalRunner 命中任一工具开关即不跑全量评估——漏登记即静默
     * 跑 267 例全量评估（生成 + Judge 真金白银）。本断言钉住「本工具与既有工具开关都在清单内」。
     */
    @Test
    void graphDirectionFlagIsRegisteredAsToolModeSoFullEvalIsSkipped() {
        assertThat(EvalRunner.TOOL_MODE_OPTIONS)
            .as("本工具开关必须登记，否则 --eval.graph-direction-ab 启动会连带跑全量评估")
            .contains(GraphDirectionAbRunner.OPTION);
        assertThat(EvalRunner.TOOL_MODE_OPTIONS)
            .as("同批补齐的历史遗漏：多跳草稿工具开关")
            .contains(MultiHopDraftRunner.OPTION);
        assertThat(EvalRunner.TOOL_MODE_OPTIONS)
            .as("既有工具开关不得被本次收敛误删")
            .contains("eval.annotate-query", "eval.annotate-all", "eval.draft-answers",
                "eval.calibration-readback", "eval.diff");
    }

    @Test
    void triggeredAcceptsBareFlagAndRejectsExplicitFalse() {
        assertThat(GraphDirectionAbRunner.triggered(appArgs("--eval.graph-direction-ab"))).isTrue();
        assertThat(GraphDirectionAbRunner.triggered(appArgs("--eval.graph-direction-ab=true"))).isTrue();
        assertThat(GraphDirectionAbRunner.triggered(appArgs("--eval.graph-direction-ab=false"))).isFalse();
        assertThat(GraphDirectionAbRunner.triggered(appArgs("--eval.probe=chain"))).isFalse();
    }

    // ── 夹具 ──

    /** 确定性脚本：方向 × 题号 → 命中序列（覆盖命中/未命中/部分命中/位次折减四形态） */
    private static Map<GraphRecords.ExpandDirection, Map<Integer, List<GraphRecords.GraphChunkHit>>> script() {
        Map<GraphRecords.ExpandDirection, Map<Integer, List<GraphRecords.GraphChunkHit>>> script =
            new EnumMap<>(GraphRecords.ExpandDirection.class);
        script.put(GraphRecords.ExpandDirection.BOTH, Map.of(
            1, List.of(hit("c1", 0, 0.9), hit("cX", 1, 0.45)),
            2, List.of(hit("c3", 0, 0.8)),
            3, List.of(hit("c9", 1, 0.4))));
        script.put(GraphRecords.ExpandDirection.OUTGOING, Map.of(1, List.of(), 2, List.of(), 3, List.of()));
        script.put(GraphRecords.ExpandDirection.INCOMING, Map.of(
            1, List.of(hit("cX", 1, 0.5), hit("c2", 1, 0.45), hit("c1", 1, 0.44)),
            2, List.of(hit("cX", 0, 0.5), hit("c3", 0, 0.49)),
            3, List.of(hit("c4", 1, 0.6))));
        script.put(GraphRecords.ExpandDirection.NONE, Map.of(
            1, List.of(hit("c1", 0, 0.9)),
            2, List.of(hit("c9", 0, 0.3)),
            3, List.of()));
        return script;
    }

    private static GraphRecords.GraphChunkHit hit(String chunkId, int hop, double score) {
        return new GraphRecords.GraphChunkHit(chunkId, "doc-" + chunkId, score, List.of("实体"), hop);
    }

    private static GoldenQAPair pair(String id, QACategory category, String question, List<String> expectedChunkIds) {
        return new GoldenQAPair(id, category, question, null, null, expectedChunkIds, List.of(), null, null, null);
    }

    private GraphDirectionAbRunner runner(GraphGateway gateway, EmbeddingModel embeddingModel, Path reportPath) {
        GraphDirectionAbRunner runner = new GraphDirectionAbRunner(gatewayProvider(gateway),
            embeddingProvider(embeddingModel), graphProperties, retrievalProperties, datasetLoader, jsonMapper);
        ReflectionTestUtils.setField(runner, "graphEnabled", true);
        ReflectionTestUtils.setField(runner, "tenantId", TENANT);
        ReflectionTestUtils.setField(runner, "reportPath", reportPath.toString());
        ReflectionTestUtils.setField(runner, "noiseBand", 0.02);
        return runner;
    }

    private static void run(GraphDirectionAbRunner runner, String... args) {
        runner.run(appArgs(args));
    }

    private static DefaultApplicationArguments appArgs(String... args) {
        return new DefaultApplicationArguments(args);
    }

    private GraphDirectionAbRunner.AbReport readReport(Path report) {
        try {
            return jsonMapper.readValue(Files.readString(report), GraphDirectionAbRunner.AbReport.class);
        } catch (Exception e) {
            throw new IllegalStateException("报告读取失败: " + report, e);
        }
    }

    private static Map<String, GraphDirectionAbRunner.DirectionReport> byDirection(GraphDirectionAbRunner.AbReport ab) {
        Map<String, GraphDirectionAbRunner.DirectionReport> map = new LinkedHashMap<>();
        ab.directions().forEach(d -> map.put(d.direction(), d));
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object value) {
        return (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<GraphGateway> gatewayProvider(GraphGateway gateway) {
        ObjectProvider<GraphGateway> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gateway);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<EmbeddingModel> embeddingProvider(EmbeddingModel embeddingModel) {
        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(embeddingModel);
        return provider;
    }

    /** 假网关（零真库）：按「方向 × 题号」脚本返回命中，并记录全部 spec 供断言 */
    private static final class RecordingFakeGateway implements GraphGateway {

        private final Map<GraphRecords.ExpandDirection, Map<Integer, List<GraphRecords.GraphChunkHit>>> script;
        private final List<GraphRecords.GraphRetrievalSpec> specs = new ArrayList<>();
        private final List<String> tenants = new ArrayList<>();

        private RecordingFakeGateway(Map<GraphRecords.ExpandDirection, Map<Integer, List<GraphRecords.GraphChunkHit>>> script) {
            this.script = script;
        }

        @Override
        public List<GraphRecords.GraphChunkHit> retrieveChunks(String tenantId, GraphRecords.GraphRetrievalSpec spec) {
            tenants.add(tenantId);
            specs.add(spec);
            int questionIndex = (int) spec.queryEmbedding()[0];   // 假向量首元素 = 题号（嵌入夹具写入）
            return script.getOrDefault(spec.expandDirection(), Map.of()).getOrDefault(questionIndex, List.of());
        }

        @Override
        public void ensureSchema() {
            throw new UnsupportedOperationException("只读扫描工具不得触达 Schema 初始化");
        }

        @Override
        public void verifyConnectivity() {
            throw new UnsupportedOperationException("只读扫描工具不得触达连通性校验");
        }

        @Override
        public void replaceDocumentGraph(String tenantId, String docId, List<GraphRecords.ChunkAnchor> chunks,
                                         List<GraphRecords.EntityWrite> entities, List<GraphRecords.RelationWrite> relations) {
            throw new UnsupportedOperationException("只读扫描工具不得写图");
        }

        @Override
        public void removeDocument(String tenantId, String docId) {
            throw new UnsupportedOperationException("只读扫描工具不得写图");
        }

        @Override
        public void setChunksDeleted(String tenantId, Collection<String> chunkIds, boolean deleted) {
            throw new UnsupportedOperationException("只读扫描工具不得写图");
        }

        @Override
        public GraphRecords.GraphRetrievalDiagnostics diagnoseRetrieval(String tenantId, float[] queryEmbedding,
                                                                        int entityFetchLimit, int wideFetchLimit,
                                                                        double similarityThreshold) {
            throw new UnsupportedOperationException("本工具不做空召回归因");
        }

        @Override
        public GraphCounts countByTenant(String tenantId) {
            throw new UnsupportedOperationException("本工具不做图规模计数");
        }

        @Override
        public List<GraphRecords.EntityChainSample> sampleEntityChains(String tenantId, int limit, int seedLimit) {
            throw new UnsupportedOperationException("本工具不做实体链采样");
        }
    }

    /** 假嵌入模型（零网络）：按题号（q1/q2/q3）产出首元素为题号的向量，并计数调用次数 */
    private static final class CountingEmbeddingModel implements EmbeddingModel {

        private int embedCalls;

        @Override
        public float[] embed(String text) {
            embedCalls++;
            return new float[]{Float.parseFloat(text.substring(1))};
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = new ArrayList<>();
            List<String> texts = request.getInstructions();
            for (int i = 0; i < texts.size(); i++) {
                embeddings.add(new Embedding(embed(texts.get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        @Override
        public int dimensions() {
            return GraphGateway.ENTITY_EMBEDDING_DIMENSIONS;
        }
    }
}
