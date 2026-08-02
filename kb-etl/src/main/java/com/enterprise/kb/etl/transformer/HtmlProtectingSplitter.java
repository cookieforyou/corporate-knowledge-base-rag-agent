package com.enterprise.kb.etl.transformer;

import com.enterprise.kb.domain.enums.ChunkType;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTML 结构保护式切分器（设计文档 9.2，任务 2.3）
 *
 * <p>表格/图片作为一等公民保护：
 * <ul>
 *   <li>{@code <table>} 块 → 独立 Chunk（chunk_type=TABLE），原文 HTML 存
 *       original_content，供前端回显与结构保真；</li>
 *   <li>{@code <img>} 块 → 独立 Chunk（chunk_type=IMAGE），original_html 存标签；
 *       vision 摘要（2.4 可选）将描述写入 content 参与检索；</li>
 *   <li>纯文本段 → TokenTextSplitter 常规切分（800/200，与 Phase 1 参数一致——
 *       快速路径即 Phase 1 行为，无保护标签的文档零行为变化）；</li>
 *   <li>小表格（文本 &lt; 30 字符）退化为纯文本，避免噪声 Chunk。</li>
 * </ul>
 */
@Component
public class HtmlProtectingSplitter implements DocumentTransformer {

    /** 小表格退化阈值（字符数）：低于此值的表格视为噪声，并入文本流 */
    private static final int MIN_TABLE_CHARS = 30;

    private final TokenTextSplitter textSplitter = newTextSplitter();

    /**
     * 文本切分器工厂（与 Phase 1 参数一致，9.2 快速路径；包内可见供回归测试）。
     * maxNumChunks=10000 是切片数上限（官方默认），非切片大小——2026-08-01 修复注记。
     */
    public static TokenTextSplitter newTextSplitter() {
        return TokenTextSplitter.builder()
            .withChunkSize(800)
            .withMinChunkSizeChars(200)
            .withMinChunkLengthToEmbed(10)
            .withMaxNumChunks(10000)
            .withKeepSeparator(true)
            .build();
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        List<Document> result = new ArrayList<>();
        for (Document doc : documents) {
            String text = doc.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (!containsProtectedTags(text)) {
                result.addAll(textSplitter.apply(List.of(doc)));   // 快速路径 = Phase 1 行为
                continue;
            }
            result.addAll(splitWithProtection(doc));
        }
        return result;
    }

    /** 廉价预检：无保护标签则免走 JSoup AST */
    private static boolean containsProtectedTags(String text) {
        String lower = text.toLowerCase();
        return lower.contains("<table") || lower.contains("<img");
    }

    private List<Document> splitWithProtection(Document doc) {
        List<Document> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (Node node : Jsoup.parseBodyFragment(doc.getText()).body().childNodes()) {
            if (node instanceof TextNode textNode) {
                buffer.append(textNode.getWholeText()).append('\n');
            } else if (node instanceof Element el) {
                switch (el.tagName().toLowerCase()) {
                    case "table" -> {
                        if (el.text().length() < MIN_TABLE_CHARS) {
                            buffer.append(el.text()).append('\n');   // 小表格退化纯文本
                        } else {
                            flush(buffer, doc, result);
                            result.add(protectedChunk(doc, el.outerHtml(), ChunkType.TABLE));
                        }
                    }
                    case "img" -> {
                        flush(buffer, doc, result);
                        result.add(protectedChunk(doc, el.outerHtml(), ChunkType.IMAGE));
                    }
                    default -> buffer.append(el.text()).append('\n');
                }
            }
        }
        flush(buffer, doc, result);
        return result;
    }

    /** 冲刷累积文本：经 TokenTextSplitter 常规切分后追加 */
    private void flush(StringBuilder buffer, Document doc, List<Document> result) {
        if (buffer.isEmpty() || buffer.toString().isBlank()) {
            buffer.setLength(0);
            return;
        }
        Document textDoc = Document.builder()
            .text(buffer.toString())
            .metadata(new HashMap<>(doc.getMetadata()))
            .build();
        result.addAll(textSplitter.apply(List.of(textDoc)));
        buffer.setLength(0);
    }

    /** 保护块独立成 Chunk：chunk_type + original_html 元数据（落库时写 kb_chunk.original_content） */
    private static Document protectedChunk(Document doc, String html, ChunkType type) {
        Map<String, Object> meta = new HashMap<>(doc.getMetadata());
        meta.put("chunk_type", type.name());
        meta.put("original_html", html);
        return Document.builder().text(html).metadata(meta).build();
    }
}
