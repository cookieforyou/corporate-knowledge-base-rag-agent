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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 结构保护式切分器（设计文档 9.2，任务 2.3；簇④ A4 增 heading 路径跟踪）
 *
 * <p>表格/图片作为一等公民保护：
 * <ul>
 *   <li>{@code <table>} 块 → 独立 Chunk（chunk_type=TABLE），原文 HTML 存
 *       original_content，供前端回显与结构保真；</li>
 *   <li>{@code <img>} 块 → 独立 Chunk（chunk_type=IMAGE），original_html 存标签；
 *       vision 摘要（2.4 可选）将描述写入 content 参与检索；</li>
 *   <li>纯文本段 → TokenTextSplitter 常规切分（800/200，与 Phase 1 参数一致）；</li>
 *   <li>小表格（文本 &lt; 30 字符）退化为纯文本，避免噪声 Chunk。</li>
 * </ul>
 *
 * <p><b>heading 路径跟踪（簇④ A4，9.2 v2.21）</b>：切分时维护六级标题栈
 * （Markdown {@code #{1,6} } 行与 HTML {@code <h1>..<h6>} 双形态识别），
 * 每个 chunk 注入 {@code heading_path} 元数据（如「产品手册 &gt; 定价 &gt; 企业版」）——
 * 展示与检索两用（kb_chunk.metadata JSONB / 向量库元数据 / ES heading_path 字段）。
 * 标题变更处冲刷文本缓冲：chunk 边界与章节边界对齐（topic-aligned），
 * 标题文字保留在新 chunk 正文首部（BM25/向量化可检索）。
 * 无保护标签且无标题的纯文本文档走原快速路径，行为零变化。
 */
@Component
public class HtmlProtectingSplitter implements DocumentTransformer {

    /** 小表格退化阈值（字符数）：低于此值的表格视为噪声，并入文本流 */
    private static final int MIN_TABLE_CHARS = 30;

    /** chunk 元数据键：标题路径（「L1 &gt; L2 &gt; …」，缺省不写键——元数据禁 null） */
    public static final String HEADING_PATH_KEY = "heading_path";

    /** Markdown 标题行：{@code #{1,6} 标题文字}（MULTILINE 供整篇预检 find()） */
    private static final Pattern MARKDOWN_HEADING =
        Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$", Pattern.MULTILINE);

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
                if (!MARKDOWN_HEADING.matcher(text).find()) {
                    result.addAll(textSplitter.apply(List.of(doc)));   // 快速路径 = Phase 1 行为
                } else {
                    // 仅标题无保护标签：纯行扫描（不经 JSoup——避免代码片段中的
                    // 尖括号被解析为未知标签丢文本）
                    result.addAll(splitByMarkdownHeadings(doc));
                }
                continue;
            }
            result.addAll(splitWithTracking(doc));
        }
        return result;
    }

    /** 无保护标签的 Markdown 标题文档：逐行扫描标题、章节对齐冲刷（免 JSoup AST） */
    private List<Document> splitByMarkdownHeadings(Document doc) {
        List<Document> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        String[] headings = new String[7];
        appendTextLines(buffer, doc, result, headings, doc.getText());
        flushBuffer(buffer, doc, result, headingPathOf(headings));
        return result;
    }

    /** 廉价预检：无保护标签则免走 JSoup AST */
    private static boolean containsProtectedTags(String text) {
        String lower = text.toLowerCase();
        return lower.contains("<table") || lower.contains("<img");
    }

    /**
     * 结构感知切分：JSoup 遍历 body 直接子节点，文本按行扫描 Markdown 标题，
     * 标题栈随遇随更新；标题变更即冲刷缓冲（chunk 与章节对齐），
     * 保护块（TABLE/IMAGE）独立成 chunk 并携带当前 heading_path。
     */
    private List<Document> splitWithTracking(Document doc) {
        List<Document> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        String[] headings = new String[7];   // 下标 1..6 = h1..h6 当前标题

        for (Node node : Jsoup.parseBodyFragment(doc.getText()).body().childNodes()) {
            if (node instanceof TextNode textNode) {
                appendTextLines(buffer, doc, result, headings, textNode.getWholeText());
            } else if (node instanceof Element el) {
                int level = headingLevelOf(el.tagName());
                if (level > 0) {
                    flushBuffer(buffer, doc, result, headingPathOf(headings));
                    setHeading(headings, level, el.text());
                    buffer.append(el.text()).append('\n');   // 标题文字保留正文（BM25/向量化可检索）
                    continue;
                }
                switch (el.tagName().toLowerCase()) {
                    case "table" -> {
                        if (el.text().length() < MIN_TABLE_CHARS) {
                            buffer.append(el.text()).append('\n');   // 小表格退化纯文本
                        } else {
                            flushBuffer(buffer, doc, result, headingPathOf(headings));
                            result.add(protectedChunk(doc, el.outerHtml(), ChunkType.TABLE, headingPathOf(headings)));
                        }
                    }
                    case "img" -> {
                        flushBuffer(buffer, doc, result, headingPathOf(headings));
                        result.add(protectedChunk(doc, el.outerHtml(), ChunkType.IMAGE, headingPathOf(headings)));
                    }
                    default -> buffer.append(el.text()).append('\n');
                }
            }
        }
        flushBuffer(buffer, doc, result, headingPathOf(headings));
        return result;
    }

    /** 文本逐行扫描：Markdown 标题行触发冲刷 + 标题栈更新，其余行入缓冲 */
    private void appendTextLines(StringBuilder buffer, Document doc, List<Document> result,
                                 String[] headings, String text) {
        for (String line : text.split("\n", -1)) {
            Matcher m = MARKDOWN_HEADING.matcher(line.stripTrailing());
            if (m.matches()) {
                flushBuffer(buffer, doc, result, headingPathOf(headings));
                String title = m.group(2).strip();
                setHeading(headings, m.group(1).length(), title);
                buffer.append(title).append('\n');   // 标题文字保留正文首部
            } else {
                buffer.append(line).append('\n');
            }
        }
    }

    /** 冲刷累积文本：携带当前 heading_path 经 TokenTextSplitter 常规切分后追加 */
    private void flushBuffer(StringBuilder buffer, Document doc, List<Document> result, String headingPath) {
        if (buffer.isEmpty() || buffer.toString().isBlank()) {
            buffer.setLength(0);
            return;
        }
        Map<String, Object> meta = new HashMap<>(doc.getMetadata());
        if (headingPath != null && !headingPath.isBlank()) {
            meta.put(HEADING_PATH_KEY, headingPath);
        }
        Document textDoc = Document.builder()
            .text(buffer.toString())
            .metadata(meta)
            .build();
        result.addAll(textSplitter.apply(List.of(textDoc)));
        buffer.setLength(0);
    }

    /** 保护块独立成 Chunk：chunk_type + original_html + heading_path 元数据 */
    private static Document protectedChunk(Document doc, String html, ChunkType type, String headingPath) {
        Map<String, Object> meta = new HashMap<>(doc.getMetadata());
        meta.put("chunk_type", type.name());
        meta.put("original_html", html);
        if (headingPath != null && !headingPath.isBlank()) {
            meta.put(HEADING_PATH_KEY, headingPath);
        }
        return Document.builder().text(html).metadata(meta).build();
    }

    /** h1..h6 → 1..6，其余标签 0 */
    private static int headingLevelOf(String tagName) {
        String tag = tagName.toLowerCase();
        if (tag.length() == 2 && tag.charAt(0) == 'h' && tag.charAt(1) >= '1' && tag.charAt(1) <= '6') {
            return tag.charAt(1) - '0';
        }
        return 0;
    }

    /** 标题入栈：同级覆盖、深层清空（「定价」h2 出现后其下 h3 失效于下一个 h2） */
    static void setHeading(String[] headings, int level, String title) {
        if (title == null || title.isBlank()) {
            return;
        }
        headings[level] = title.strip();
        for (int i = level + 1; i <= 6; i++) {
            headings[i] = null;
        }
    }

    /** 当前标题栈 → 「L1 &gt; L2 &gt; …」路径；无标题返回空串 */
    static String headingPathOf(String[] headings) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            if (headings[i] != null) {
                if (!sb.isEmpty()) {
                    sb.append(" > ");
                }
                sb.append(headings[i]);
            }
        }
        return sb.toString();
    }
}
