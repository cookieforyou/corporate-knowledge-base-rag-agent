package com.enterprise.kb.eval.runner;

import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.eval.dataset.GoldenDatasetLoader;
import com.enterprise.kb.eval.dataset.GoldenQAPair;
import com.enterprise.kb.eval.dataset.QACategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * expectedAnswer 草稿生成器单测（簇② 批2）——真值材料装配与回落语义
 */
class AnswerDraftRunnerTest {

    private KbChunkRepository chunkRepository;
    private RetrievalProbe probe;
    private AnswerDraftRunner runner;

    @BeforeEach
    void setUp() {
        chunkRepository = mock(KbChunkRepository.class);
        probe = mock(RetrievalProbe.class);
        when(probe.getOrder()).thenReturn(0);
        when(probe.name()).thenReturn("hybrid");
        runner = new AnswerDraftRunner(mock(GoldenDatasetLoader.class), chunkRepository,
            List.of(probe), mock(ChatClient.class), JsonMapper.builder().build());
    }

    private static GoldenQAPair pair(String id, List<String> expectedChunkIds) {
        return new GoldenQAPair(id, QACategory.FACTOID, "问题-" + id,
            null, null, expectedChunkIds, null, null, null, null);
    }

    private static KbChunk chunk(String id, String content, String original, boolean deleted) {
        KbChunk c = new KbChunk();
        c.setId(id);
        c.setDocId("doc-" + id);
        c.setChunkIndex(0);
        c.setContent(content);
        c.setOriginalContent(original);
        c.setIsDeleted(deleted);
        return c;
    }

    // ── joinChunks：内容优先级 ──

    @Test
    void joinChunksPrefersContentFallsBackToOriginalAndSkipsBlank() {
        String joined = AnswerDraftRunner.joinChunks(List.of(
            chunk("c1", "消毒后正文", "原文", false),
            chunk("c2", "  ", "仅原文可用", false),
            chunk("c3", null, null, false)));

        assertThat(joined)
            .contains("[c1]\n消毒后正文")
            .contains("[c2]\n仅原文可用")
            .doesNotContain("c3");
    }

    // ── truthContext：真值材料三态 ──

    @Test
    void expectedChunksAliveYieldZeroCircularTruth() {
        when(chunkRepository.findAllById(List.of("c1", "c2")))
            .thenReturn(List.of(
                chunk("c1", "正文一", "原文一", false),
                chunk("c2", "正文二", "原文二", true)));   // 软删排除

        AnswerDraftRunner.TruthContext truth = runner.truthContext(pair("f-01", List.of("c1", "c2")));

        assertThat(truth.source()).isEqualTo(AnswerDraftRunner.SOURCE_EXPECTED_CHUNKS);
        assertThat(truth.context()).contains("正文一").doesNotContain("正文二");
        verifyNoInteractions(probe);   // 真值直查：零检索触达 = 零循环
    }

    @Test
    void repositoryMissFallsBackToProbeCandidates() {
        when(chunkRepository.findAllById(List.of("gone"))).thenReturn(List.of());
        when(probe.probe(anyString(), anyInt())).thenReturn(List.of(
            new RetrievalProbe.ProbeHit("p1", "a.md", "候选正文", 0.9)));

        AnswerDraftRunner.TruthContext truth = runner.truthContext(pair("f-02", List.of("gone")));

        assertThat(truth.source()).isEqualTo(AnswerDraftRunner.SOURCE_RETRIEVAL_FALLBACK);
        assertThat(truth.context()).contains("[p1]").contains("候选正文");
    }

    @Test
    void noChunkAnnotationGoesStraightToProbe() {
        when(probe.probe(anyString(), anyInt())).thenReturn(List.of(
            new RetrievalProbe.ProbeHit("p1", "a.md", "候选正文", 0.9)));

        AnswerDraftRunner.TruthContext truth = runner.truthContext(pair("f-03", null));

        assertThat(truth.source()).isEqualTo(AnswerDraftRunner.SOURCE_RETRIEVAL_FALLBACK);
        assertThat(truth.context()).contains("候选正文");
        verifyNoInteractions(chunkRepository);
    }
}
