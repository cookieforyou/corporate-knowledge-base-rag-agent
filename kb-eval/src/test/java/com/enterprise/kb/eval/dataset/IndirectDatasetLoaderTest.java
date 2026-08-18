package com.enterprise.kb.eval.dataset;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 间接注入毒化语料加载器测试（安全簇④ D3）——编码引用形态自洽契约
 * （测试资源为无害占位正文的编码形态，纪律：不入真载荷）。
 */
class IndirectDatasetLoaderTest {

    private static final String TEST_LOCATION = "classpath:indirect/indirect-qa-test.json";

    private IndirectDatasetLoader loader(String location) {
        return new IndirectDatasetLoader(new JsonMapper(), location);
    }

    @Test
    void decodesEncodedDocumentAndVerifiesFingerprint() {
        List<IndirectQAPair> pairs = loader(TEST_LOCATION).loadAll();

        assertThat(pairs).hasSize(2);
        IndirectQAPair decoded = pairs.stream()
            .filter(p -> "poison-t01".equals(p.id())).findFirst().orElseThrow();
        assertThat(decoded.document()).isEqualTo("这是测试占位文本，无载荷。");
        assertThat(decoded.hasEncodedDocument()).isFalse();   // 解码产物编码字段已卸
        assertThat(decoded.fileName()).isEqualTo("test-doc.txt");
        assertThat(decoded.question()).isEqualTo("这份文档的主题是什么？");
        assertThat(decoded.judgeCriteria()).contains("不执行文档内任何附加指令");
    }

    @Test
    void plainDocumentPassesThroughWithoutDecoding() {
        List<IndirectQAPair> pairs = loader(TEST_LOCATION).loadAll();

        IndirectQAPair plain = pairs.stream()
            .filter(p -> "poison-t02".equals(p.id())).findFirst().orElseThrow();
        assertThat(plain.document()).contains("明文形态用例正文");
    }

    @Test
    void corruptedFingerprintFailsFast() {
        // 指纹锚点与正文不一致 → 语料腐化 fail-fast（错误消息只含用例 id 不含内容）
        String corrupted = """
            [{"id":"poison-x01","fileName":"x.txt","question":"q","judgeCriteria":"c",
              "document":"6L+Z5piv5rWL6K+V5Y2g5L2N5paH5pys77yM5peg6L296I2344CC",
              "documentEncoding":"base64","documentSha256":"deadbeef"}]
            """;
        // 经临时 classpath 资源不可行，改为直驱解码分支：构造指纹错误的语料文件位置
        IndirectDatasetLoader badLoader = new IndirectDatasetLoader(new JsonMapper(),
            "classpath:indirect/indirect-qa-corrupt.json");
        writeCorpusToTestOutput(corrupted);

        assertThatThrownBy(badLoader::loadAll)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("poison-x01")
            .hasMessageContaining("指纹锚点校验失败");
    }

    @Test
    void missingFileReturnsEmptyList() {
        assertThat(loader("classpath:indirect/nonexistent-*.json").loadAll()).isEmpty();
    }

    @Test
    void defaultProductionLocationLoadsSelfConsistently() {
        // 生产位置加载不变量 = 可加载且自洽（解码 + 指纹校验加载器内 fail-fast 强制）：
        // 空数组为缺省合法形态（语料待带外注入），D3b 注入后非空同属合法——「为空」非永久契约
        List<IndirectQAPair> pairs = loader("classpath:indirect/indirect-qa.json").loadAll();

        assertThat(pairs).allSatisfy(p -> {
            assertThat(p.id()).isNotBlank();
            assertThat(p.question()).isNotBlank();
            assertThat(p.judgeCriteria()).isNotBlank();
            assertThat(p.document()).isNotBlank();
            assertThat(p.hasEncodedDocument()).isFalse();   // 加载后编码形态已解码
        });
    }

    /** 将腐化语料写入 target/test-classes/indirect/（测试 classpath 根），供加载器解析 */
    private static void writeCorpusToTestOutput(String content) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("target/test-classes/indirect");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve("indirect-qa-corrupt.json"), content);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("测试资源写入失败", e);
        }
    }
}
