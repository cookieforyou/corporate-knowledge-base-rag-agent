package com.enterprise.kb.eval.runner;

import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.eval.dataset.GoldenDatasetLoader;
import com.enterprise.kb.eval.dataset.GoldenQAPair;
import com.enterprise.kb.eval.metric.JudgePrompts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * expectedAnswer 机器侧草稿生成器（簇② 批2，用户定案「机器侧草稿 + 人工审定」）
 *
 * <p>用法：{@code mvn spring-boot:run -pl kb-eval -Dspring-boot.run.arguments=--eval.draft-answers}
 *
 * <p>对全部正向用例（非注入非负向、expectedAnswer 未标注）逐条起草理想回答：
 * 真值材料优先取用例 expectedChunkIds 对应的 {@code kb_chunk} 原文
 * （{@link KbChunkRepository#findAllById} 直查——<b>零循环</b>：草稿不依赖检索质量，
 * 标注真值独立于被度量的检索链路）；标注缺失/全部失效时回落探针 Top-8 候选
 * （审定表显式标记 {@code RETRIEVAL_FALLBACK}，人工重点复核）。
 *
 * <p>起草走 Judge 模型（qwen3.7-plus，temperature 0 / 思考关）——与被测模型
 * 跨厂商隔离，草稿独立性同 Judge 独立性（16.3 纪律）。产出双通道：
 * {@code target/expected-answer-drafts.json}（机读，回灌工具可消费）+
 * {@code target/expected-answer-drafts.md}（审定表）。人工审定修订后按
 * {@code golden/README-标注指南.md} 工作流回写各语料的 expectedAnswer 字段
 * （Git Ops——回写属语料变更，走正常提交，不经运行时通道）。
 */
@Slf4j
@Component
public class AnswerDraftRunner implements ApplicationRunner {

    /** 草稿真值材料来源 */
    static final String SOURCE_EXPECTED_CHUNKS = "EXPECTED_CHUNKS";
    static final String SOURCE_RETRIEVAL_FALLBACK = "RETRIEVAL_FALLBACK";

    private final GoldenDatasetLoader datasetLoader;
    private final KbChunkRepository chunkRepository;
    private final RetrievalProbe probe;
    private final ChatClient judgeChatClient;
    private final JsonMapper jsonMapper;

    public AnswerDraftRunner(GoldenDatasetLoader datasetLoader,
                             KbChunkRepository chunkRepository,
                             List<RetrievalProbe> probes,
                             @Qualifier("judgeChatClient") ChatClient judgeChatClient,
                             JsonMapper jsonMapper) {
        this.datasetLoader = datasetLoader;
        this.chunkRepository = chunkRepository;
        this.probe = probes.stream()
            .min(Comparator.comparingInt(RetrievalProbe::getOrder))
            .orElseThrow();
        this.judgeChatClient = judgeChatClient;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("eval.draft-answers")) {
            return;
        }
        List<GoldenQAPair> targets = datasetLoader.loadAll().stream()
            .filter(p -> !p.isInjection() && !p.isNegative())
            .filter(p -> p.expectedAnswer() == null || p.expectedAnswer().isBlank())
            .toList();
        if (targets.isEmpty()) {
            log.info("═══ 无需起草：全部正向用例 expectedAnswer 已标注 ═══");
            return;
        }
        log.info("═══ expectedAnswer 起草：{} 条待标注正向用例，起草模型走 Judge 通道 ═══", targets.size());

        List<DraftEntry> entries = new ArrayList<>();
        int done = 0;
        for (GoldenQAPair pair : targets) {
            TruthContext truth = truthContext(pair);
            String draft = null;
            try {
                draft = judgeChatClient.prompt().user(String.format(
                    JudgePrompts.ANSWER_DRAFT, pair.question(), truth.context())).call().content();
            } catch (Exception e) {
                log.warn("[{}] 起草失败（保留真值材料，人工可直接编写）: {}", pair.id(), e.getMessage());
            }
            entries.add(new DraftEntry(pair.id(), pair.category().name(), pair.question(),
                truth.source(), draft));
            if (++done % 10 == 0) {
                log.info("起草进度：{}/{}", done, targets.size());
            }
        }

        try {
            Path outDir = Path.of("target");
            Files.createDirectories(outDir);
            Files.writeString(outDir.resolve("expected-answer-drafts.json"),
                jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(entries)
                    + System.lineSeparator());
            Files.writeString(outDir.resolve("expected-answer-drafts.md"), renderReviewSheet(entries));
            long fallback = entries.stream()
                .filter(e -> SOURCE_RETRIEVAL_FALLBACK.equals(e.source())).count();
            log.info("═══ 草稿已落盘：target/expected-answer-drafts.json / .md（共 {} 条；其中检索回落 {} 条需重点审定）═══",
                entries.size(), fallback);
        } catch (Exception e) {
            throw new IllegalStateException("expectedAnswer 草稿落盘失败", e);
        }
    }

    /** 草稿条目（JSON 机读 + 审定表共用） */
    record DraftEntry(String id, String category, String question, String source, String draft) {}

    /** 真值材料：来源标记 + 拼接后上下文 */
    record TruthContext(String source, String context) {}

    /**
     * 真值材料装配：优先 expectedChunkIds 直查 PG（排除软删；内容取消毒后正文，
     * 缺失回退原文）；标注缺失或查库零命中 → 探针 Top-8 回落（起草可用，但草稿
     * 循环依赖检索质量，审定表标记重点复核）。
     */
    TruthContext truthContext(GoldenQAPair pair) {
        if (pair.expectedChunkIds() != null && !pair.expectedChunkIds().isEmpty()) {
            List<KbChunk> chunks = chunkRepository.findAllById(pair.expectedChunkIds()).stream()
                .filter(c -> !Boolean.TRUE.equals(c.getIsDeleted()))
                .sorted(Comparator.comparing(KbChunk::getDocId)
                    .thenComparing(KbChunk::getChunkIndex, Comparator.nullsLast(Integer::compareTo)))
                .toList();
            String joined = joinChunks(chunks);
            if (!joined.isBlank()) {
                return new TruthContext(SOURCE_EXPECTED_CHUNKS, joined);
            }
            log.warn("[{}] expectedChunkIds 查库零存活命中，回落探针候选", pair.id());
        }
        List<RetrievalProbe.ProbeHit> hits;
        try {
            hits = probe.probe(pair.question(), 8);
        } catch (Exception e) {
            log.warn("[{}] 探针回落失败: {}", pair.id(), e.getMessage());
            hits = List.of();
        }
        String fallback = hits.stream()
            .map(h -> "[" + h.chunkId() + "]\n" + (h.content() == null ? "" : h.content()))
            .collect(java.util.stream.Collectors.joining("\n\n"));
        return new TruthContext(SOURCE_RETRIEVAL_FALLBACK, fallback);
    }

    /** chunk 真值拼接：[chunkId] 标头 + 正文（正文缺失回退原文），跳过全空条目 */
    static String joinChunks(List<KbChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (KbChunk c : chunks) {
            String body = c.getContent() != null && !c.getContent().isBlank()
                ? c.getContent() : c.getOriginalContent();
            if (body == null || body.isBlank()) {
                continue;
            }
            sb.append("[").append(c.getId()).append("]\n").append(body).append("\n\n");
        }
        return sb.toString();
    }

    private String renderReviewSheet(List<DraftEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("# expectedAnswer 草稿审定表（簇② 批2）").append(System.lineSeparator());
        sb.append(System.lineSeparator());
        sb.append("审定工作流：逐条核对草稿与知识库事实——").append(System.lineSeparator());
        sb.append("1. 草稿正确 → 原文采纳；有误/不全 → 直接修订；无可取处 → 重写")
            .append("（机器侧草稿 + 人工审定定案，人工是最终事实源）").append(System.lineSeparator());
        sb.append("2. 审定稿回写对应语料 `golden/*.json` 的 `expectedAnswer` 字段（工作流见 ")
            .append("golden/README-标注指南.md），随语料变更正常提交").append(System.lineSeparator());
        sb.append("3. `source = RETRIEVAL_FALLBACK` 条目草稿基于检索候选（非标注真值），重点复核")
            .append(System.lineSeparator());
        sb.append(System.lineSeparator()).append("---").append(System.lineSeparator());
        for (DraftEntry e : entries) {
            sb.append(String.format("%n## %s（%s，source=%s）%n%n", e.id(), e.category(), e.source()));
            sb.append("**问题**：").append(e.question()).append(System.lineSeparator());
            sb.append(System.lineSeparator()).append("**机器草稿**：").append(System.lineSeparator());
            sb.append(System.lineSeparator())
                .append(e.draft() == null || e.draft().isBlank() ? "（起草失败，请人工直接编写）" : e.draft())
                .append(System.lineSeparator());
            sb.append(System.lineSeparator()).append("**审定结论**：☐ 采纳 ☐ 修订 ☐ 重写")
                .append(System.lineSeparator());
        }
        return sb.toString();
    }
}
