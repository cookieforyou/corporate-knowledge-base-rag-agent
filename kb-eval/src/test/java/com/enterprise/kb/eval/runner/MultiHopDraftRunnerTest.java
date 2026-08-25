package com.enterprise.kb.eval.runner;

import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.infrastructure.graph.GraphGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 多跳候选题草稿工具单测（簇④ 批4）——前置守卫 + 摘录装配 + 审定表渲染。
 */
class MultiHopDraftRunnerTest {

    private KbChunkRepository chunkRepository;
    private MultiHopDraftRunner runner;

    @SuppressWarnings("unchecked")
    private static ObjectProvider<GraphGateway> gatewayProvider(GraphGateway gateway) {
        ObjectProvider<GraphGateway> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gateway);
        return provider;
    }

    @BeforeEach
    void setUp() {
        chunkRepository = mock(KbChunkRepository.class);
        runner = new MultiHopDraftRunner(gatewayProvider(null), chunkRepository,
            JsonMapper.builder().build());
        ReflectionTestUtils.setField(runner, "tenantId", "tenant-a");
        ReflectionTestUtils.setField(runner, "draftSampleSize", 30);
    }

    @Test
    void unrelatedArgsSkipSilently() {
        // 非 --eval.draft-multihop 触发：直接返回，无网关不报错
        runner.run(new DefaultApplicationArguments("--eval.draft-answers"));
    }

    @Test
    void missingGatewayFailsWithGraphDisabledHint() {
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments("--eval.draft-multihop")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("rag.graph.enabled");
    }

    @Test
    void missingTenantFailsWithTenantHint() {
        MultiHopDraftRunner r = new MultiHopDraftRunner(gatewayProvider(mock(GraphGateway.class)),
            chunkRepository, JsonMapper.builder().build());
        ReflectionTestUtils.setField(r, "tenantId", "");
        assertThatThrownBy(() -> r.run(new DefaultApplicationArguments("--eval.draft-multihop")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("eval.chain-probe.tenant-id");
    }

    private static KbChunk chunk(String id, String content, boolean deleted) {
        KbChunk c = new KbChunk();
        c.setId(id);
        c.setContent(content);
        c.setIsDeleted(deleted);
        return c;
    }

    @Test
    void excerptsSkipSoftDeletedAndTruncateLongContent() {
        String longText = "甲".repeat(500);
        when(chunkRepository.findAllById(anyList())).thenReturn(List.of(
            chunk("c1", "短内容", false),
            chunk("c2", longText, false),
            chunk("c3", "已软删", true)));

        Map<String, String> excerpts = runner.excerptsOf(List.of("c1", "c2", "c3"));

        assertThat(excerpts).containsKeys("c1", "c2").doesNotContainKey("c3");
        assertThat(excerpts.get("c1")).isEqualTo("短内容");
        assertThat(excerpts.get("c2")).endsWith("…").hasSizeLessThan(500);
    }

    @Test
    void emptyChunkIdsYieldEmptyExcerpts() {
        assertThat(runner.excerptsOf(List.of())).isEmpty();
        assertThat(runner.excerptsOf(null)).isEmpty();
    }

    @Test
    void reviewSheetRendersChainAndChunkIds() {
        var materials = List.of(new MultiHopDraftRunner.DraftMaterial(
            "mh-draft-001", List.of("a公司", "张工", "某大学"),
            List.of("c1", "c2"), Map.of("c1", "片段一", "c2", "片段二")));

        String sheet = runner.renderReviewSheet(materials);

        assertThat(sheet)
            .contains("mh-draft-001")
            .contains("a公司 → 张工 → 某大学")
            .contains("`c1`").contains("`c2`")
            .contains("片段一");
    }
}
