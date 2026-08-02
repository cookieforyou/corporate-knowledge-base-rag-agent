package com.enterprise.kb.etl.service;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.enterprise.kb.etl.transformer.HtmlProtectingSplitter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ETL 切分配置回归测试
 *
 * <p>防回归：2026-08-01 修复 maxNumChunks=5 误配——该参数是「单文档最大切片数」，
 * 触顶后 Spring AI TokenTextSplitter 将全部尾部剩余并入单个尾块，长文档尾块
 * 超过 embedding 模型单条输入上限（TokenCountBatchingStrategy 默认 8192×0.9 前置
 * 拒绝），ETL 在 EMBEDDING 阶段整体失败。测试以与 cl100k 同源的 jtokkit 编码器
 * 直接断言每个切片位于拒绝线以下。
 */
class DocumentSplittingTest {

    /** 与 TokenTextSplitter 内部同源编码器（cl100k_base） */
    private static final Encoding CL100K =
        Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    /** TokenCountBatchingStrategy 默认拒绝线：8192 × 0.9 */
    private static final int EMBEDDING_INPUT_GUARD = (int) (8192 * 0.9);

    @Test
    void longDocumentFullySplitWithoutOversizedTailChunk() {
        // 中英混合长文（含标点，模拟真实技术文档）
        String text = "Domain-Driven Design is a software design methodology for complex systems. ".repeat(100)
            + "领域驱动设计将领域模型作为软件的核心，通过限界上下文划分边界，聚合根保证一致性边界。".repeat(400);
        int totalTokens = CL100K.countTokens(text);

        List<Document> chunks = HtmlProtectingSplitter.newTextSplitter().apply(List.of(new Document(text)));

        // 切片数不被人为封顶：历史误配下 20K+ tokens 的文档只会得到 6 片（5+1 尾块）
        assertThat(chunks.size())
            .as("长文档切片数应按 ~800 tokens/片分布，不得被 maxNumChunks 封顶")
            .isGreaterThan(totalTokens / 1000);

        // 每片都在 embedding 单条输入拒绝线以下（历史故障的直接根因）
        for (Document chunk : chunks) {
            assertThat(CL100K.countTokens(chunk.getText()))
                .as("单片 token 数必须低于 embedding 前置拒绝线")
                .isLessThan(EMBEDDING_INPUT_GUARD);
        }

        // 内容基本不丢（切分边界 trim 产生轻微损耗）
        int totalChars = chunks.stream().mapToInt(c -> c.getText().length()).sum();
        assertThat(totalChars)
            .as("切分后总字符数应保持原文主体")
            .isGreaterThan((int) (text.trim().length() * 0.85));
    }

    @Test
    void shortDocumentRemainsSingleChunk() {
        List<Document> chunks = HtmlProtectingSplitter.newTextSplitter()
            .apply(List.of(new Document("增值税发票认证期限为三百六十天。")));
        assertThat(chunks).hasSize(1);
    }
}
