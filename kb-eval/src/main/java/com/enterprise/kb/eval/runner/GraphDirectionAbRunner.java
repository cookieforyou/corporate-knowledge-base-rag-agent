package com.enterprise.kb.eval.runner;

import com.enterprise.kb.ai.config.GraphRetrievalProperties;
import com.enterprise.kb.ai.config.RetrievalProperties;
import com.enterprise.kb.eval.dataset.GoldenDatasetLoader;
import com.enterprise.kb.eval.dataset.GoldenQAPair;
import com.enterprise.kb.eval.dataset.QACategory;
import com.enterprise.kb.eval.metric.RetrievalMetrics;
import com.enterprise.kb.infrastructure.graph.GraphGateway;
import com.enterprise.kb.infrastructure.graph.GraphRecords;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图路展开方向 A/B 扫描工具（{@code --eval.graph-direction-ab}）——为
 * {@code rag.graph.retrieval.expand-direction} 的<b>缺省值定调</b>提供召回侧数据。
 *
 * <p><b>工具目的</b>：图模型是有向的（抽取提示词产出 {@code WORKS_AT / PART_OF /
 * DEPENDS_ON / PRODUCED_BY} 等方向性类型），而 1 跳邻域展开是<b>召回机制</b>而非逻辑推理
 * （见 {@link GraphRecords.ExpandDirection} javadoc）。缺省 {@code BOTH} 保持既有召回行为，
 * 是否值得切 {@code OUTGOING} / {@code INCOMING}（或干脆 {@code NONE}）须以多跳测试集
 * （{@code golden/multihop-qa.json}，30 条 MULTI_HOP）实测召回对比定调——本工具即该实测的执行体：
 * 对四个方向取值逐个跑同一批问题，输出「方向 × 指标」对比表 + 结论行 + JSON 报告。
 *
 * <p><b>零生成 LLM / 严格只读</b>：全流程只做两件事——① 查询向量化（{@link EmbeddingModel}，
 * 每条问题<b>只嵌入一次</b>，四方向复用同一向量）；② {@link GraphGateway#retrieveChunks}
 * 图路检索（只读，网关内部仅 Cypher 读查询）。<b>不调用</b>任何 ChatModel / Judge / 生成链，
 * <b>不写</b> PG / ES / 向量库 / Redis，不触发抽取与回填；对既有评估流程零副作用
 * （非触发参数下 {@link #run} 首行即返回）。
 *
 * <p><b>前置条件</b>：
 * <ol>
 *   <li>{@code rag.graph.enabled=true}（否则 {@code GraphGateway} Bean 条件装配缺位）；</li>
 *   <li>图内已有该租户的图覆盖（抽取已跑 / 存量回填 {@code POST /api/v1/admin/graph/backfill}），
 *       且实体描述嵌入与查询嵌入同源同维（1024，{@code GraphGateway.ENTITY_EMBEDDING_DIMENSIONS}）；</li>
 *   <li>已配置 embedding 密钥（{@code DASHSCOPE_API_KEY}，百炼 OpenAI 兼容端点）；</li>
 *   <li>租户经 {@code eval.chain-probe.tenant-id} 指定（与 {@link ChainRetrievalProbe} /
 *       {@link MultiHopDraftRunner} 同键，缺失时打印中文提示并正常退出）。</li>
 * </ol>
 * 以上前置任一不满足时：打印清晰中文提示并<b>正常退出</b>（不抛异常击穿启动，
 * 与 {@code MultiHopDraftRunner} / {@code ChainRetrievalProbe} 的守卫口径不同点在于
 * 本工具为纯度量旁路，缺前置时「无数据可报」比「启动失败」更合适）。
 *
 * <p><b>运行命令</b>（仓库根执行；同 {@link MultiHopDraftRunner} 形态——单模块跑须兄弟模块已 install，
 * Maven 4 纪律）：
 * <pre>{@code
 * # 1) 裸开关形态（report 落缺省 target/graph-direction-ab.json）
 * EVAL_TENANT_ID=tenant_001 RAG_GRAPH_ENABLED=true \
 *   mvn -q --no-transfer-progress spring-boot:run -pl kb-eval \
 *     -Dspring-boot.run.arguments="--eval.graph-direction-ab"
 *
 * # 2) 显式 true + 自定义报告路径
 * EVAL_TENANT_ID=tenant_001 RAG_GRAPH_ENABLED=true \
 *   mvn -q --no-transfer-progress spring-boot:run -pl kb-eval \
 *     -Dspring-boot.run.arguments="--eval.graph-direction-ab=true --eval.graph-direction-ab.report=target/graph-direction-ab-v302.json"
 *
 * # 3) 显式 false：不执行（首行返回，零副作用）
 * mvn -q --no-transfer-progress spring-boot:run -pl kb-eval \
 *   -Dspring-boot.run.arguments="--eval.graph-direction-ab=false"
 * }</pre>
 *
 * <p><b>指标定义（chunk 级，全部以 {@code GraphGateway} 返回的 chunk 命中序列为输入）</b>：
 * <ul>
 *   <li><b>hit@k</b>：至少命中一个期望 chunk 的题目占比（题目级命中率）——
 *       回答「这一方向能不能把多跳题的两端片段捞回来」；</li>
 *   <li><b>recall@k 均值</b>：逐题 {@code |命中 ∩ 期望| / |期望|} 的算术平均
 *       （复用 {@link RetrievalMetrics#recallAtK}）——度量片段覆盖完整度；</li>
 *   <li><b>MRR</b>：逐题首个命中排名的倒数（{@link RetrievalMetrics#reciprocalRank}）的算术平均
 *       ——度量命中位置是否靠前（融合/重排前的原始位次质量）；</li>
 *   <li><b>平均耗时</b>：每方向单题的 {@code retrieveChunks} 墙钟均值（毫秒，不含嵌入；
 *       嵌入成本四方向共用一次，单独记 {@code avgEmbedMillis}；含首题可能的连接冷启动）；</li>
 *   <li><b>命中跳数分布</b>：全方向累计的 {@code hop=0}（种子实体直连 chunk）与
 *       {@code hop≥1}（邻域展开贡献 chunk）命中计数——解释方向差异来自种子还是展开。</li>
 * </ul>
 * 其中 {@code k} = <b>生产图路实际召回上限</b> {@code rag.retrieval.top-k × recall-multiplier}
 * （{@code HybridDocumentRetriever} 传给图路的 {@code recallSize}），非重排后的最终证据条数。
 *
 * <p><b>与生产 spec 的映射对照</b>（唯一差异 = 展开方向为扫描变量；其余逐字段抄
 * {@code GraphDocumentRetriever} 的映射，保证 A/B 读数反映生产行为）：
 * <pre>
 * spec 字段              ← 配置键 / 生产公式
 * queryEmbedding         ← EmbeddingModel.embed(question)（每条问题一次）
 * entityTopN             ← rag.graph.retrieval.entity-top-n
 * entityFetchLimit       ← max(entityTopN, entityTopN × max(1, rag.graph.retrieval.entity-over-fetch))
 * similarityThreshold    ← rag.graph.retrieval.entity-similarity-threshold
 * expandDirection        ← 扫描变量（BOTH / OUTGOING / INCOMING / NONE）
 * candidateLimit         ← max(rag.graph.retrieval.entity-top-n, rag.graph.retrieval.candidate-limit)
 * neighborLimit          ← rag.graph.retrieval.neighbor-limit 直传（v3.04 邻域上限；非正值由网关回落候选上限）
 * limit                  ← rag.retrieval.top-k × rag.retrieval.recall-multiplier
 * </pre>
 * 注：生产映射在 {@code rag.graph.retrieval.expand-neighbors=false} 时把方向强制为 {@code NONE}
 * （方向键失效）。本工具为让「切方向是否值得」这一问题可测，<b>直接按扫描值传方向</b>，
 * 并在该开关为 false 时于控制台与报告（{@code directionGateInert=true}）显式标注
 * 「本表为 what-if 形态，生产当前恒为 NONE」。
 *
 * <p><b>边界（务必与生成侧指标区分）</b>：本工具<b>不产出任何生成质量结论</b>——没有
 * LLM 生成、没有 Judge、没有 AC/CA/HR 读数，只给<b>召回侧证据</b>（图路单路的 chunk 级
 * 命中/覆盖/位次）。方向缺省值的最终定调仍须以本报告的结论行作输入，复跑全量
 * {@code kb-eval} 基线（三路融合 + 重排后的端到端读数）后决定，理由：图路是 RRF 三路之一，
 * 单路召回增益可能在融合/重排后被抵消（或反向）。
 *
 * <p><b>配置键</b>：
 * <ul>
 *   <li>{@code eval.graph-direction-ab}（开关，裸开关或 {@code =true} 触发，{@code =false} 不执行）</li>
 *   <li>{@code eval.graph-direction-ab.report}（缺省 {@code target/graph-direction-ab.json}）</li>
 *   <li>{@code eval.graph-direction-ab.noise-band}（缺省 {@code 0.02}：结论判「差异不显著」的
 *       Δrecall@k 与 ΔMRR 双阈值，两指标都落在带内才判噪声）</li>
 *   <li>租户 {@code eval.chain-probe.tenant-id}；图开关 {@code rag.graph.enabled}</li>
 * </ul>
 */
@Slf4j
@Component
public class GraphDirectionAbRunner implements ApplicationRunner {

    /** 触发开关（裸开关或 {@code =true} 执行；{@code =false} 首行返回） */
    static final String OPTION = "eval.graph-direction-ab";

    /** 报告缺省落盘路径（相对工作目录；对齐既有 runner 的 target/ 落盘纪律） */
    static final String DEFAULT_REPORT_PATH = "target/graph-direction-ab.json";

    /** 对比基准方向（缺省值候选；结论在噪声带内时明确建议保持本方向） */
    static final GraphRecords.ExpandDirection BASELINE_DIRECTION = GraphRecords.ExpandDirection.BOTH;

    /**
     * 扫描顺序（固定四方向；BOTH 在首 = 缺省候选，输出表与报告的排序跨次复跑可比，
     * 且指标并列时排序 tie-break 优先靠前者）。
     */
    static final List<GraphRecords.ExpandDirection> SCAN_DIRECTIONS = List.of(
        GraphRecords.ExpandDirection.BOTH,
        GraphRecords.ExpandDirection.OUTGOING,
        GraphRecords.ExpandDirection.INCOMING,
        GraphRecords.ExpandDirection.NONE);

    /** 指标排序（recall@k 主序、MRR 次序、扫描序 tie-break）——并列时取更靠前者 */
    private static final Comparator<DirectionReport> BY_METRIC =
        Comparator.comparingDouble(DirectionReport::recallAtK)
            .thenComparingDouble(DirectionReport::mrr)
            .thenComparingInt(r -> -SCAN_DIRECTIONS.indexOf(GraphRecords.ExpandDirection.valueOf(r.direction())));

    private final ObjectProvider<GraphGateway> graphGatewayProvider;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final GraphRetrievalProperties graphProperties;
    private final RetrievalProperties retrievalProperties;
    private final GoldenDatasetLoader datasetLoader;
    private final JsonMapper jsonMapper;

    @Value("${rag.graph.enabled:false}")
    private boolean graphEnabled;

    @Value("${eval.chain-probe.tenant-id:}")
    private String tenantId;

    @Value("${eval.graph-direction-ab.report:" + DEFAULT_REPORT_PATH + "}")
    private String reportPath;

    @Value("${eval.graph-direction-ab.noise-band:0.02}")
    private double noiseBand;

    public GraphDirectionAbRunner(ObjectProvider<GraphGateway> graphGatewayProvider,
                                  ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                  GraphRetrievalProperties graphProperties,
                                  RetrievalProperties retrievalProperties,
                                  GoldenDatasetLoader datasetLoader,
                                  JsonMapper jsonMapper) {
        this.graphGatewayProvider = graphGatewayProvider;
        this.embeddingModelProvider = embeddingModelProvider;
        this.graphProperties = graphProperties;
        this.retrievalProperties = retrievalProperties;
        this.datasetLoader = datasetLoader;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!triggered(args)) {
            return;   // 非触发形态：零副作用（不触达网关、不嵌入、不落盘）
        }
        if (!graphEnabled) {
            log.info("═══ 图路展开方向 A/B 未执行：图谱未启用（rag.graph.enabled=false）——"
                + "本工具为图路单路度量，须先置 rag.graph.enabled=true 并完成抽取/回填（图内已有租户图覆盖）═══");
            return;
        }
        GraphGateway gateway = graphGatewayProvider.getIfAvailable();
        if (gateway == null) {
            log.info("═══ 图路展开方向 A/B 未执行：GraphGateway Bean 缺位（rag.graph.enabled=true 才条件装配"
                + "——请核对 rag.graph.enabled 与 Neo4j 连接段 spring.neo4j.*）═══");
            return;
        }
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            log.info("═══ 图路展开方向 A/B 未执行：EmbeddingModel Bean 缺位——"
                + "须配置 embedding 密钥（DASHSCOPE_API_KEY）与 spring.ai.openai.embedding.* 端点═══");
            return;
        }
        if (tenantId == null || tenantId.isBlank()) {
            log.info("═══ 图路展开方向 A/B 未执行：未设置 eval.chain-probe.tenant-id"
                + "（图路检索租户域必填，缺失即 fail-closed 零触达——请设 EVAL_TENANT_ID=<语料租户>）═══");
            return;
        }
        List<GoldenQAPair> questions = multihopQuestions();
        if (questions.isEmpty()) {
            log.info("═══ 图路展开方向 A/B 未执行：golden/multihop-qa.json 无可用样本"
                + "（须 category=MULTI_HOP 且 expectedChunkIds 非空；出题材料经 --eval.draft-multihop 起草）═══");
            return;
        }
        int recallSize = recallSize();

        // ① 每条问题只嵌入一次（四方向复用同一向量）——嵌入是付费外部调用，成本纪律
        List<EmbeddedQuestion> embedded = new ArrayList<>(questions.size());
        long embedStart = System.nanoTime();
        for (GoldenQAPair question : questions) {
            float[] vector;
            try {
                vector = embeddingModel.embed(question.question());
            } catch (Exception e) {
                log.error("═══ 图路展开方向 A/B 中止：查询向量化失败（检查 embedding 密钥 DASHSCOPE_API_KEY、"
                    + "base-url 与网络）: id={}, 原因={} ═══", question.id(), e.getMessage());
                return;
            }
            embedded.add(new EmbeddedQuestion(question, vector));
        }
        double avgEmbedMillis = round((System.nanoTime() - embedStart) / 1_000_000.0 / embedded.size());

        // ② 四方向逐个扫描（同一向量、同一生产同源 spec，仅方向不同）
        List<DirectionReport> reports = new ArrayList<>(SCAN_DIRECTIONS.size());
        for (GraphRecords.ExpandDirection direction : SCAN_DIRECTIONS) {
            List<QuestionDetail> details = new ArrayList<>(embedded.size());
            for (EmbeddedQuestion question : embedded) {
                List<GraphRecords.GraphChunkHit> hits;
                long start = System.nanoTime();
                try {
                    hits = gateway.retrieveChunks(tenantId, specFor(question.embedding(), direction, recallSize));
                } catch (Exception e) {
                    log.error("═══ 图路展开方向 A/B 中止：图路检索失败（检查 Neo4j 连通性、"
                        + "spring.neo4j.* 出借前探活与租户图覆盖）: direction={}, id={}, 原因={} ═══",
                        direction, question.pair().id(), e.getMessage());
                    return;
                }
                long millis = (System.nanoTime() - start) / 1_000_000;
                details.add(toDetail(question.pair(), hits, millis));
            }
            reports.add(summarize(direction, details));
        }

        String ranking = renderRanking(reports);
        String conclusion = conclude(reports, noiseBand);
        log.info(System.lineSeparator() + renderConsole(embedded.size(), recallSize, reports, ranking, conclusion,
            !graphProperties.isExpandNeighbors()));

        writeReport(new AbReport(
            tenantId,
            embedded.size(),
            recallSize,
            noiseBand,
            BASELINE_DIRECTION.name(),
            graphProperties.isExpandNeighbors(),
            !graphProperties.isExpandNeighbors(),
            specMapping(),
            avgEmbedMillis,
            reports,
            ranking,
            conclusion));
    }

    /** 触发判定：{@code --eval.graph-direction-ab}（裸）或 {@code =true} 执行；{@code =false} 不执行 */
    static boolean triggered(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return false;
        }
        List<String> values = args.getOptionValues(OPTION);
        if (values == null || values.isEmpty()) {
            return true;   // 裸开关形态
        }
        return values.stream().allMatch(v -> v == null || v.isBlank() || Boolean.parseBoolean(v));
    }

    /** 多跳测试集样本（category=MULTI_HOP 且带 expectedChunkIds；其余分类与无期望样本不参与 chunk 级指标） */
    private List<GoldenQAPair> multihopQuestions() {
        return datasetLoader.loadAll().stream()
            .filter(pair -> pair.category() == QACategory.MULTI_HOP)
            .filter(GoldenQAPair::hasRetrievalExpectation)
            .toList();
    }

    /**
     * 生产同源 spec（唯一差异 = 方向为扫描变量）——逐字段对齐
     * {@code GraphDocumentRetriever#doRetrieve} / {@code #fetchLimit} / {@code #candidateLimit}
     * （v3.04 单种子邻域上限 {@code neighbor-limit} 与生产同形直传：非正值由网关回落候选上限，
     * 规则单点在网关，不在调用方重算）。
     */
    GraphRecords.GraphRetrievalSpec specFor(float[] queryEmbedding,
                                            GraphRecords.ExpandDirection direction,
                                            int recallSize) {
        int seedLimit = graphProperties.getEntityTopN();
        int fetchLimit = Math.max(seedLimit, seedLimit * Math.max(1, graphProperties.getEntityOverFetch()));
        int candidateLimit = Math.max(graphProperties.getEntityTopN(), graphProperties.getCandidateLimit());
        return new GraphRecords.GraphRetrievalSpec(queryEmbedding, seedLimit, fetchLimit,
            graphProperties.getEntitySimilarityThreshold(), direction, candidateLimit,
            graphProperties.getNeighborLimit(), recallSize);
    }

    /** 召回上限 k = 生产图路实际收到的 recallSize（topK × 召回倍数，非重排后证据条数） */
    private int recallSize() {
        return Math.max(1, retrievalProperties.getTopK() * Math.max(1, retrievalProperties.getRecallMultiplier()));
    }

    /** 逐题明细：命中序列 → chunk 级指标（复用 kb-eval 既有纯函数，零重复实现） */
    static QuestionDetail toDetail(GoldenQAPair pair, List<GraphRecords.GraphChunkHit> hits, long millis) {
        List<String> retrieved = hits.stream().map(GraphRecords.GraphChunkHit::chunkId).toList();
        List<String> expected = pair.expectedChunkIds();
        double recall = RetrievalMetrics.recallAtK(retrieved, expected);
        double reciprocalRank = RetrievalMetrics.reciprocalRank(retrieved, expected);
        int hop0Hits = (int) hits.stream().filter(hit -> hit.hop() == 0).count();
        return new QuestionDetail(pair.id(), recall > 0, recall, reciprocalRank, retrieved.size(),
            retrieved, List.copyOf(expected), millis, hop0Hits, hits.size() - hop0Hits);
    }

    /** 单方向汇总：hit@k（题目级命中占比）+ recall@k 均值 + MRR + 平均耗时 + 跳数分布 */
    static DirectionReport summarize(GraphRecords.ExpandDirection direction, List<QuestionDetail> details) {
        int total = details.size();
        double hitAtK = total == 0 ? 0.0
            : round(details.stream().filter(QuestionDetail::hit).count() / (double) total);
        double recallAtK = round(details.stream().mapToDouble(QuestionDetail::recallAtK).average().orElse(0.0));
        double mrr = round(details.stream().mapToDouble(QuestionDetail::reciprocalRank).average().orElse(0.0));
        double avgRetrieveMillis = round(details.stream().mapToDouble(QuestionDetail::retrieveMillis).average().orElse(0.0));
        int hop0Hits = details.stream().mapToInt(QuestionDetail::hop0Hits).sum();
        int hop1PlusHits = details.stream().mapToInt(QuestionDetail::hop1PlusHits).sum();
        double avgHits = round(details.stream().mapToInt(QuestionDetail::retrievedCount).average().orElse(0.0));
        return new DirectionReport(direction.name(), total, hitAtK, recallAtK, mrr, avgRetrieveMillis,
            hop0Hits, hop1PlusHits, avgHits, List.copyOf(details));
    }

    /**
     * 结论行（按 recall@k 均值与 MRR 排序给出数据建议）：
     * 最佳方向相对基准 {@link #BASELINE_DIRECTION} 的 Δrecall@k / ΔMRR <b>双双</b>落在噪声带
     * {@code eval.graph-direction-ab.noise-band} 内 → 明确写「差异不显著，保持 BOTH」；
     * 否则给出 {@code expand-direction=<最佳方向>} 的数据建议（并提示须复跑全量基线定调）。
     */
    static String conclude(List<DirectionReport> reports, double noiseBand) {
        DirectionReport baseline = reports.stream()
            .filter(r -> BASELINE_DIRECTION.name().equals(r.direction()))
            .findFirst()
            .orElse(reports.get(0));
        DirectionReport best = reports.stream().max(BY_METRIC).orElse(baseline);
        double deltaRecall = round(best.recallAtK() - baseline.recallAtK());
        double deltaMrr = round(best.mrr() - baseline.mrr());
        boolean withinNoise = deltaRecall <= noiseBand && deltaMrr <= noiseBand;

        if (withinNoise) {
            boolean identical = reports.stream().allMatch(r ->
                r.recallAtK() == baseline.recallAtK() && r.mrr() == baseline.mrr()
                    && r.hitAtK() == baseline.hitAtK());
            String head = identical
                ? "四方向读数完全一致（本租户图上展开方向不影响召回：可能 expand-neighbors=false、"
                    + "邻居边未覆盖或期望片段全为种子直连）——"
                : "";
            return head + ("差异不显著，保持 %s：最佳方向 %s 相对基准 Δrecall@k=%+.4f、ΔMRR=%+.4f，"
                + "均落在噪声带 ±%.4f 内（无实质增益，不构成切换依据）。")
                .formatted(BASELINE_DIRECTION.name(), best.direction(), deltaRecall, deltaMrr, noiseBand);
        }
        return ("数据建议：rag.graph.retrieval.expand-direction=%s（recall@k %.4f / MRR %.4f，"
            + "相对基准 %s 的 Δrecall@k=%+.4f、ΔMRR=%+.4f 超出噪声带 ±%.4f）——"
            + "仍须复跑全量 kb-eval 基线（三路融合 + 重排后读数）确认增益未被融合/重排抵消。")
            .formatted(best.direction(), best.recallAtK(), best.mrr(), BASELINE_DIRECTION.name(),
                deltaRecall, deltaMrr, noiseBand);
    }

    /** 排序行（recall@k 主序、MRR 次序）——结论行的排序依据显式可核 */
    static String renderRanking(List<DirectionReport> reports) {
        List<DirectionReport> sorted = new ArrayList<>(reports);
        sorted.sort(BY_METRIC.reversed());
        StringBuilder sb = new StringBuilder("排序（recall@k ↓, MRR ↓）：");
        for (int i = 0; i < sorted.size(); i++) {
            DirectionReport r = sorted.get(i);
            if (i > 0) {
                sb.append(" > ");
            }
            sb.append("%s(%.4f/%.4f)".formatted(r.direction(), r.recallAtK(), r.mrr()));
        }
        return sb.toString();
    }

    /** 控制台对比表（方向 × 指标）+ 图注 + 排序行 + 结论行 */
    static String renderConsole(int questionCount, int recallSize, List<DirectionReport> reports,
                                String ranking, String conclusion, boolean directionGateInert) {
        StringBuilder sb = new StringBuilder();
        sb.append("═══ 图路展开方向 A/B 扫描（零生成 LLM，仅图路召回侧读数）═══").append(System.lineSeparator());
        sb.append("题目=%d（MULTI_HOP 且带 expectedChunkIds）  k=%d（生产图路 recallSize）  嵌入=每方向复用同一向量"
            .formatted(questionCount, recallSize)).append(System.lineSeparator());
        if (directionGateInert) {
            sb.append("⚠ 当前 rag.graph.retrieval.expand-neighbors=false：生产映射把方向强制为 NONE"
                + "（方向键失效），下表四方向为 what-if 形态（直接按扫描值传方向）")
                .append(System.lineSeparator());
        }
        sb.append(String.format("%-9s %8s %10s %8s %10s %6s %7s %9s%n",
            "direction", "hit@k", "recall@k", "MRR", "avg_ms", "hop0", "hop1+", "avg_hits"));
        for (DirectionReport r : reports) {
            sb.append(String.format("%-9s %8.4f %10.4f %8.4f %10.1f %6d %7d %9.2f%n",
                r.direction(), r.hitAtK(), r.recallAtK(), r.mrr(), r.avgRetrieveMillis(),
                r.hop0Hits(), r.hop1PlusHits(), r.avgHitsPerQuestion()));
        }
        sb.append("指标：hit@k=至少命中一个期望 chunk 的题目占比；recall@k=逐题 |命中∩期望|/|期望| 均值；"
            + "MRR=逐题首个命中倒数排名均值；avg_ms=单题图路检索均值（不含嵌入，含首题可能的连接冷启动）；"
            + "hop0/hop1+=命中跳数分布计数（种子直连 / 邻域展开贡献）").append(System.lineSeparator());
        sb.append(ranking).append(System.lineSeparator());
        sb.append("结论：").append(conclusion).append(System.lineSeparator());
        sb.append("边界：本工具不产出生成质量结论（无 LLM 生成/Judge/AC/CA/HR），只给召回侧证据。");
        return sb.toString();
    }

    /** 报告自描述字段：配置键 → spec 字段的对照（人读报告无需回源码核映射） */
    private Map<String, Object> specMapping() {
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("rag.graph.retrieval.entity-top-n → entityTopN", graphProperties.getEntityTopN());
        mapping.put("rag.graph.retrieval.entity-over-fetch → entityFetchLimit 倍数",
            graphProperties.getEntityOverFetch());
        mapping.put("rag.graph.retrieval.entity-similarity-threshold → similarityThreshold",
            graphProperties.getEntitySimilarityThreshold());
        mapping.put("rag.graph.retrieval.candidate-limit → candidateLimit（下限 entityTopN）",
            graphProperties.getCandidateLimit());
        mapping.put("rag.graph.retrieval.neighbor-limit → neighborLimit（非正值回落候选上限）",
            graphProperties.getNeighborLimit());
        mapping.put("rag.graph.retrieval.expand-neighbors → 生产方向门（false 强制 NONE）",
            graphProperties.isExpandNeighbors());
        mapping.put("rag.retrieval.top-k × rag.retrieval.recall-multiplier → limit",
            retrievalProperties.getTopK() + " × " + retrievalProperties.getRecallMultiplier());
        return mapping;
    }

    /** JSON 报告落盘（缺省 target/graph-direction-ab.json）——失败只记日志，不击穿启动 */
    private void writeReport(AbReport report) {
        Path path = Path.of(reportPath);
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report)
                + System.lineSeparator());
            log.info("═══ 图路方向 A/B 报告已落盘：{}（含每方向指标与 {} 条逐题明细）═══",
                path.toAbsolutePath(), report.questionCount());
        } catch (Exception e) {
            log.error("═══ 图路方向 A/B 报告落盘失败（读数见上方控制台表；不影响启动）: path={}, 原因={} ═══",
                reportPath, e.getMessage());
        }
    }

    /** 指标四位小数截断——报告与控制台读数一致、跨次复跑可比 */
    static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    /** 问题 + 其唯一一次嵌入产物（四方向复用） */
    private record EmbeddedQuestion(GoldenQAPair pair, float[] embedding) {
    }

    /** 逐题明细（报告 details 元素：命中序列 + 算分 + 耗时 + 跳数分布） */
    public record QuestionDetail(
        String id,
        boolean hit,
        double recallAtK,
        double reciprocalRank,
        int retrievedCount,
        List<String> retrievedChunkIds,
        List<String> expectedChunkIds,
        long retrieveMillis,
        int hop0Hits,
        int hop1PlusHits) {
    }

    /** 单方向指标（报告 directions 元素；含该方向逐题明细） */
    public record DirectionReport(
        String direction,
        int questionCount,
        double hitAtK,
        double recallAtK,
        double mrr,
        double avgRetrieveMillis,
        int hop0Hits,
        int hop1PlusHits,
        double avgHitsPerQuestion,
        List<QuestionDetail> details) {
    }

    /** A/B 报告根（JSON 落盘形态；specMapping 自描述生产同源映射） */
    public record AbReport(
        String tenantId,
        int questionCount,
        int recallSize,
        double noiseBand,
        String baselineDirection,
        boolean expandNeighbors,
        boolean directionGateInert,
        Map<String, Object> specMapping,
        double avgEmbedMillis,
        List<DirectionReport> directions,
        String ranking,
        String conclusion) {
    }
}
