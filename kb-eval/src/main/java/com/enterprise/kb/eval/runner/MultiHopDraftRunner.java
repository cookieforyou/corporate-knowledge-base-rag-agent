package com.enterprise.kb.eval.runner;

import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.infrastructure.graph.GraphGateway;
import com.enterprise.kb.infrastructure.graph.GraphRecords;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多跳候选题草稿工具（簇④ 5.2 批4，{@code --eval.draft-multihop}）——
 * 沿用簇② AC「机器侧草稿 + 人工审定」先例的选题材料形态。
 *
 * <p><b>形态</b>：图谱二跳实体链（a→b→c）采样 + 链首/尾实体关联的存活
 * chunk 原文摘录，产出「多跳候选题材料表」——多跳题即「经桥接实体 b
 * 关联 a 与 c」的跨片段推理，材料表给出出题真值（实体链 + chunk 摘录 +
 * chunk ID），<b>问题与标准答案由人工审定编写</b>（零 LLM 调用，成本纪律
 * 同 AnswerDraftRunner 的 PG 直查形态），审定回写
 * {@code golden/multihop-qa.json}（category=MULTI_HOP，须标注
 * expectedAnswer + expectedChunkIds，门禁消费 AC 通过率 ≥80%）。
 *
 * <p><b>前置</b>：{@code rag.graph.enabled=true}（网关 Bean 在场）+
 * 存量回填已跑（图覆盖存在）+ {@code eval.chain-probe.tenant-id} 设置。
 *
 * <p>用法：{@code mvn spring-boot:run -pl kb-eval -Dspring-boot.run.arguments=--eval.draft-multihop}
 */
@Slf4j
@Component
public class MultiHopDraftRunner implements ApplicationRunner {

    /** 单条摘录截断长度（材料表可读性） */
    private static final int EXCERPT_CHARS = 240;

    private final ObjectProvider<GraphGateway> graphGatewayProvider;
    private final KbChunkRepository chunkRepository;
    private final JsonMapper jsonMapper;

    @Value("${eval.chain-probe.tenant-id:}")
    private String tenantId;

    @Value("${eval.multi-hop.draft-sample-size:30}")
    private int draftSampleSize;

    public MultiHopDraftRunner(ObjectProvider<GraphGateway> graphGatewayProvider,
                               KbChunkRepository chunkRepository,
                               JsonMapper jsonMapper) {
        this.graphGatewayProvider = graphGatewayProvider;
        this.chunkRepository = chunkRepository;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("eval.draft-multihop")) {
            return;
        }
        GraphGateway gateway = graphGatewayProvider.getIfAvailable();
        if (gateway == null) {
            throw new IllegalStateException(
                "--eval.draft-multihop 需启用图谱（rag.graph.enabled=true，GraphGateway Bean 缺位）");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException(
                "--eval.draft-multihop 需设置 eval.chain-probe.tenant-id（图采样租户域必填）");
        }

        List<GraphRecords.EntityChainSample> chains = gateway.sampleEntityChains(tenantId, draftSampleSize);
        if (chains.isEmpty()) {
            log.info("═══ 无二跳实体链样本——请先跑存量回填（POST /api/v1/admin/graph/backfill）建图 ═══");
            return;
        }
        log.info("═══ 多跳候选题材料起草：{} 条实体链样本（租户 {}）═══", chains.size(), tenantId);

        List<DraftMaterial> materials = new ArrayList<>();
        for (int i = 0; i < chains.size(); i++) {
            GraphRecords.EntityChainSample chain = chains.get(i);
            materials.add(new DraftMaterial(
                "mh-draft-%03d".formatted(i + 1),
                chain.entityNames(),
                chain.chunkIds(),
                excerptsOf(chain.chunkIds())));
        }

        try {
            Path outDir = Path.of("target");
            Files.createDirectories(outDir);
            Files.writeString(outDir.resolve("multihop-drafts.json"),
                jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(materials)
                    + System.lineSeparator());
            Files.writeString(outDir.resolve("multihop-drafts.md"), renderReviewSheet(materials));
            log.info("═══ 多跳材料已落盘：target/multihop-drafts.json / .md（{} 条）═══", materials.size());
            log.info("后续：人工审定编写 question/expectedAnswer → golden/multihop-qa.json"
                + "（category=MULTI_HOP，expectedChunkIds 取材料表 chunk ID，≥{} 例后门禁生效）",
                materials.size());
        } catch (Exception e) {
            throw new IllegalStateException("多跳材料落盘失败", e);
        }
    }

    /** chunk 原文摘录（PG 事实源；软删/失主丢弃）——包内可见供单测覆盖 */
    Map<String, String> excerptsOf(List<String> chunkIds) {
        Map<String, String> excerpts = new HashMap<>();
        if (chunkIds == null || chunkIds.isEmpty()) {
            return excerpts;
        }
        for (KbChunk chunk : chunkRepository.findAllById(chunkIds)) {
            if (Boolean.TRUE.equals(chunk.getIsDeleted()) || chunk.getContent() == null) {
                continue;
            }
            String text = chunk.getContent().strip();
            excerpts.put(chunk.getId(),
                text.length() <= EXCERPT_CHARS ? text : text.substring(0, EXCERPT_CHARS) + "…");
        }
        return excerpts;
    }

    /** 审定表渲染——包内可见供单测覆盖 */
    String renderReviewSheet(List<DraftMaterial> materials) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 多跳候选题材料表（簇④ 5.2，机器侧起草 + 人工审定）\n\n");
        sb.append("> 每条材料 = 图谱二跳实体链（链首 →桥接 → 链尾）+ 链首/尾关联 chunk 摘录。\n");
        sb.append("> 审定动作：基于材料编写跨片段推理问题与标准答案，回写 `golden/multihop-qa.json`\n");
        sb.append("> （category=MULTI_HOP；expectedChunkIds 取下表 chunk ID；expectedAnswer 必填——\n");
        sb.append("> 门禁以 AC≥4.0 判通过，通过率 ≥80%；样本 ≥5 条门禁才生效）。\n\n");
        for (DraftMaterial m : materials) {
            sb.append(String.format("## %s — 实体链：%s%n", m.id(), String.join(" → ", m.entityChain())));
            if (m.chunkIds().isEmpty()) {
                sb.append("（无关联存活 chunk——建议弃用本链）\n\n");
                continue;
            }
            for (String chunkId : m.chunkIds()) {
                sb.append(String.format("- `%s`%n", chunkId));
                String excerpt = m.excerpts().get(chunkId);
                if (excerpt != null) {
                    sb.append("  > ").append(excerpt.replace("\n", " ")).append("\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 草稿材料条目：实体链 + chunk 真值 + 摘录（问题/答案留白待审定） */
    public record DraftMaterial(
        String id,
        List<String> entityChain,
        List<String> chunkIds,
        Map<String, String> excerpts) {
    }
}
