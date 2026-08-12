package com.enterprise.kb.etl.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确定性 chunk ID 回归（簇④ A4 修复，9.3 v2.22）
 *
 * <p>动机：随机 UUID 方案下全量重入库令所有 chunk 换新 ID，Golden
 * expectedChunkIds 整体失配——2026-08-12 a4-heading-only 复跑检索三指标全 0.000。
 * 确定性 ID（文档名+序号+增强前原文）令重入库/contextual A/B 两臂 ID 逐位复现。
 */
class DeterministicChunkIdTest {

    @Test
    void sameInputs_reproduceSameId() {
        String a = DocumentEtlService.deterministicChunkId("产品手册.pdf", 3, "企业版定价十万");
        String b = DocumentEtlService.deterministicChunkId("产品手册.pdf", 3, "企业版定价十万");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void differentIndex_changesId() {
        String a = DocumentEtlService.deterministicChunkId("产品手册.pdf", 3, "同一段正文");
        String b = DocumentEtlService.deterministicChunkId("产品手册.pdf", 4, "同一段正文");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void differentContent_changesId() {
        String a = DocumentEtlService.deterministicChunkId("产品手册.pdf", 3, "正文甲");
        String b = DocumentEtlService.deterministicChunkId("产品手册.pdf", 3, "正文乙");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void differentDoc_changesId() {
        String a = DocumentEtlService.deterministicChunkId("产品手册.pdf", 3, "同一段正文");
        String b = DocumentEtlService.deterministicChunkId("财务制度.docx", 3, "同一段正文");
        assertThat(a).isNotEqualTo(b);
    }

    /**
     * contextual A/B 两臂可比的核心不变量：增强后 content 带「【上下文】」前缀，
     * 但 baseText 取增强前原文（original_text），故增强与否 ID 不变。
     */
    @Test
    void enrichedAndPlain_sameOriginalText_sameId() {
        String original = "企业版定价为每年十万元。";
        String plain = DocumentEtlService.deterministicChunkId("产品手册.pdf", 0, original);
        // 模拟增强臂：baseText 仍传原文（content 前缀不参与散列）
        String enriched = DocumentEtlService.deterministicChunkId("产品手册.pdf", 0, original);
        assertThat(plain).isEqualTo(enriched);
    }

    @Test
    void nullDocName_fallsBackToUnknown() {
        assertThat(DocumentEtlService.deterministicChunkId(null, 0, "正文"))
            .isEqualTo(DocumentEtlService.deterministicChunkId("unknown", 0, "正文"));
    }

    @Test
    void idIsWellFormedUuid() {
        String id = DocumentEtlService.deterministicChunkId("a.pdf", 0, "x");
        assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-3[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
