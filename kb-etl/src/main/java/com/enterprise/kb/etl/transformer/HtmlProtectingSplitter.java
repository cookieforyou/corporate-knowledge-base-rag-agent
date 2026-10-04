package com.enterprise.kb.etl.transformer;

import com.enterprise.kb.commons.constant.Constants;
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
 * HTML 结构保护式切分器（设计文档 9.2，任务 2.3；冲刺簇④ A4 heading 路径跟踪）
 *
 * <p>表格/图片作为一等公民保护：
 * <ul>
 *   <li>{@code <table>} 块 → 独立 Chunk（chunk_type=TABLE），原文 HTML 存
 *       original_html（落库 kb_chunk.original_content），供前端回显与结构保真；</li>
 *   <li>{@code <img>} 块 → 独立 Chunk（chunk_type=IMAGE），original_html 存标签；
 *       vision 摘要（2.4 可选）将描述写入 content 参与检索；</li>
 *   <li>纯文本段 → TokenTextSplitter 常规切分（800/200，与 Phase 1 参数一致）；</li>
 *   <li>小表格（文本 &lt; 30 字符）退化为纯文本，避免噪声 Chunk。</li>
 * </ul>
 *
 * <p><b>heading 路径跟踪（冲刺簇④ A4，9.2 v2.21）</b>：切分时维护六级标题栈
 * （Markdown {@code #{1,6} } 行与 HTML {@code <h1>..<h6>} 双形态识别），
 * 每个 chunk 注入 {@code heading_path} 元数据（如「产品手册 &gt; 定价 &gt; 企业版」）——
 * 展示与检索两用（kb_chunk.metadata JSONB / 向量库元数据 / ES heading_path 字段）。
 * 标题变更处冲刷文本缓冲：chunk 边界与章节边界对齐（topic-aligned），
 * 标题文字保留在新 chunk 正文首部（BM25/向量化可检索）。
 *
 * <p><b>修复批 1（2026-10-04，9.2 v2.22）——结构判据精确化 + 代码围栏屏蔽</b>：
 * <ol>
 *   <li><b>统一入口判据</b>：结构标签判据 {@code <(table|img|h[1-6])(?=[\s/>])}
 *       （严格边界，替换原 {@code contains("<table")}——后者会把 {@code <tableau>}/
 *       {@code <image>} 误判入 AST 路径，实测还可因 JSoup 吞标签致整篇零 chunk）；
 *       含 HTML 标题标签的文档一并走 AST 路径，修掉「纯 HTML 标题文档走快速路径、
 *       heading_path 全丢且标签原样进正文」的缺陷；</li>
 *   <li><b>代码围栏屏蔽</b>：一次行扫描得出围栏区间（``` / ~~~ / 4+ 反引号 / 未闭合至文末），
 *       围栏内不参与结构判据与标题判定；AST 路径把围栏区间改写为
 *       {@code <pre>转义原文</pre>}（转义而非哨兵占位——控制字符会被 HTML 解析器丢弃，
 *       哨兵方案实测污染真实语料正文），围栏内的 {@code # 注释} 与 {@code <table>}/{@code <h1>}
 *       不再被当作真标题/真结构；</li>
 *   <li><b>判据细节</b>：ATX 标题按 CommonMark 收紧为
 *       {@code ^[ \t]{0,3}(#{1,6})[ \t]+(.+?)[ \t]*$}（支持 ≤3 空格缩进；
 *       {@code [ \t]} 替换 {@code \s} 消除预检跨行匹配；去掉 MULTILINE 改逐行 matches）；
 *       结构判据剔除 HTML 注释（{@code <!-- <table> -->} 不触发 AST 路径）；</li>
 *   <li><b>空标题守卫</b>：{@code <h2></h2>} / {@code <h2>&nbsp;</h2>} 不再触发无意义冲刷
 *       （原实现先冲刷后判空 → 章节被劈成两个 chunk，NBSP 还可能进 heading_path）。</li>
 * </ol>
 * 无结构标签且无标题的纯文本文档走快速路径，行为零变化（真实语料 6 篇逐 chunk 字节一致）。
 */
@Component
public class HtmlProtectingSplitter implements DocumentTransformer {

    /** 小表格退化阈值（字符数）：低于此值的表格视为噪声，并入文本流 */
    private static final int MIN_TABLE_CHARS = 30;

    /** chunk 元数据键：标题路径（「L1 &gt; L2 &gt; …」，缺省不写键——元数据禁 null） */
    public static final String HEADING_PATH_KEY = Constants.Retrieval.META_HEADING_PATH;

    /** 保护块原文 HTML（保护式切分写 ↔ DocumentEtlService 落 kb_chunk.original_content） */
    public static final String ORIGINAL_HTML_KEY = "original_html";

    /**
     * 保护标签判据（9.2 v2.22）：严格边界——标签名后必须跟空白 / {@code >} / {@code /}，
     * 大小写不敏感。原 {@code contains("<table")} 会把 {@code <tableau>}、{@code <image>}
     * 误判入 AST 路径。
     */
    private static final Pattern PROTECTED_TAG =
        Pattern.compile("<(table|img)(?=[\\s/>])", Pattern.CASE_INSENSITIVE);

    /** HTML 标题标签判据（同严格边界）：纯 HTML 标题文档也须走 AST 路径取 heading_path */
    private static final Pattern HTML_HEADING_TAG =
        Pattern.compile("<h[1-6](?=[\\s/>])", Pattern.CASE_INSENSITIVE);

    /**
     * Markdown ATX 标题（9.2 v2.22 收紧）：CommonMark 口径——最多 3 个空格/制表符缩进、
     * 井号后至少一个空白、标题文字非空。逐行 {@code matches()} 使用（无 MULTILINE），
     * {@code [ \t]} 替换 {@code \s} 以消除整篇 {@code find()} 预检的跨行误匹配。
     */
    private static final Pattern MARKDOWN_HEADING =
        Pattern.compile("^[ \\t]{0,3}(#{1,6})[ \\t]+(.+?)[ \\t]*$");

    /** 围栏代码块标记行（``` 或 ~~~，≤3 空格缩进） */
    private static final Pattern FENCE = Pattern.compile("^[ \\t]{0,3}(`{3,}|~{3,})(.*)$");

    /** HTML 注释：仅用于结构判据剔除（注释内的 `<table>` 不应触发 AST 路径） */
    private static final Pattern HTML_COMMENT = Pattern.compile("(?s)<!--.*?-->");

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
            result.addAll(splitOne(doc, text));
        }
        return result;
    }

    /** 统一入口三路分发（9.2 v2.22）：结构判据只看围栏与注释之外的内容 */
    private List<Document> splitOne(Document doc, String text) {
        String[] lines = text.split("\n", -1);
        Scan scan = scan(lines);
        if (!scan.hasProtected && !scan.hasHtmlHeading && !scan.hasMarkdownHeading) {
            return new ArrayList<>(textSplitter.apply(List.of(doc)));      // 快速路径：原文零行为变化
        }
        if (!scan.hasProtected && !scan.hasHtmlHeading) {
            // 仅 Markdown 标题：纯行扫描（不经 JSoup——避免代码片段中的尖括号被解析为未知标签丢文本）
            return splitByMarkdownHeadings(doc, lines, scan.inFence);
        }
        return splitWithTracking(doc, jsoupText(lines, scan.inFence));
    }

    // ── 结构判据：一次行扫描得出围栏区间 + 三判据（9.2 v2.22） ──

    /** 行扫描结果：围栏区间标记 + 保护标签 / HTML 标题 / Markdown 标题三判据 */
    private record Scan(boolean[] inFence, boolean hasProtected, boolean hasHtmlHeading, boolean hasMarkdownHeading) {}

    private static Scan scan(String[] lines) {
        boolean[] inFence = new boolean[lines.length];
        StringBuilder outside = new StringBuilder();
        boolean hasMarkdownHeading = false;
        int i = 0;
        while (i < lines.length) {
            Matcher open = FENCE.matcher(lines[i]);
            if (!open.matches()) {
                outside.append(lines[i]).append('\n');
                if (!hasMarkdownHeading && MARKDOWN_HEADING.matcher(lines[i].stripTrailing()).matches()) {
                    hasMarkdownHeading = true;
                }
                i++;
                continue;
            }
            // 围栏起始：同字符、长度不短于起始、无附加文字的标记行才闭合；未闭合则延伸至文末
            String fence = open.group(1);
            char fenceChar = fence.charAt(0);
            int end = lines.length - 1;
            for (int j = i + 1; j < lines.length; j++) {
                Matcher close = FENCE.matcher(lines[j]);
                if (close.matches() && close.group(1).charAt(0) == fenceChar
                    && close.group(1).length() >= fence.length() && close.group(2).isBlank()) {
                    end = j;
                    break;
                }
            }
            for (int j = i; j <= end; j++) {
                inFence[j] = true;
            }
            i = end + 1;
        }
        // 标签判据：剔除 HTML 注释（注释内的结构标签不触发 AST 路径）
        String probe = HTML_COMMENT.matcher(outside.toString()).replaceAll(" ");
        return new Scan(inFence, PROTECTED_TAG.matcher(probe).find(),
            HTML_HEADING_TAG.matcher(probe).find(), hasMarkdownHeading);
    }

    /**
     * AST 解析视图：围栏区间改写为 {@code <pre>转义原文</pre>}——围栏内容对 JSoup 不可见
     * （不会变成真标题/真表格），由 {@code pre} 字面分支原样回到 chunk。
     * 用 HTML 转义而非哨兵占位：控制字符会被 HTML 解析器丢弃，哨兵方案实测污染语料正文。
     */
    private static String jsoupText(String[] lines, boolean[] inFence) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            if (!inFence[i]) {
                out.append(lines[i]).append('\n');
                i++;
                continue;
            }
            int end = i;
            while (end + 1 < lines.length && inFence[end + 1]) {
                end++;
            }
            StringBuilder fenceText = new StringBuilder();
            for (int j = i; j <= end; j++) {
                fenceText.append(lines[j]);
                if (j < end) {
                    fenceText.append('\n');
                }
            }
            out.append("<pre>").append(escapeHtml(fenceText.toString())).append("</pre>\n");
            i = end + 1;
        }
        return out.toString();
    }

    /** 仅转义 HTML 元字符（JSoup 解析时还原为原文） */
    private static String escapeHtml(String raw) {
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ── 路径 1：仅 Markdown 标题的文档（逐行扫描，围栏区间不判标题） ──

    private List<Document> splitByMarkdownHeadings(Document doc, String[] lines, boolean[] inFence) {
        List<Document> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        String[] headings = new String[7];
        for (int i = 0; i < lines.length; i++) {
            Matcher m = MARKDOWN_HEADING.matcher(lines[i].stripTrailing());
            if (!inFence[i] && m.matches()) {
                String title = normalizeTitle(m.group(2));
                if (!title.isEmpty()) {
                    flushBuffer(buffer, doc, result, headingPathOf(headings));
                    setHeading(headings, m.group(1).length(), title);
                    buffer.append(title).append('\n');   // 标题文字保留正文首部
                    continue;
                }
            }
            buffer.append(lines[i]).append('\n');
        }
        flushBuffer(buffer, doc, result, headingPathOf(headings));
        return result;
    }

    /**
     * 路径 2：结构感知切分——JSoup 遍历 body 直接子节点，文本按行扫描 Markdown 标题，
     * 标题栈随遇随更新；标题变更即冲刷缓冲（chunk 与章节对齐），
     * 保护块（TABLE/IMAGE）独立成 chunk 并携带当前 heading_path。
     *
     * <p>批 2 将改为递归遍历（嵌套 {@code <div>}/{@code <section>} 内的标题与保护块当前不识别）。
     */
    private List<Document> splitWithTracking(Document doc, String text) {
        List<Document> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        String[] headings = new String[7];   // 下标 1..6 = h1..h6 当前标题

        for (Node node : Jsoup.parseBodyFragment(text).body().childNodes()) {
            if (node instanceof TextNode textNode) {
                appendTextLines(buffer, doc, result, headings, textNode.getWholeText());
            } else if (node instanceof Element el) {
                int level = headingLevelOf(el.tagName());
                if (level > 0) {
                    onHtmlHeading(el, level, doc, result, headings, buffer);
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
                    case "pre", "code" -> buffer.append(el.wholeText()).append('\n');   // 字面内容（含围栏载体）
                    default -> buffer.append(el.text()).append('\n');
                }
            }
        }
        flushBuffer(buffer, doc, result, headingPathOf(headings));
        return result;
    }

    /** HTML 标题：空标题（含 NBSP 伪标题）不冲刷、不入栈——避免无意义章节边界（9.2 v2.22） */
    private void onHtmlHeading(Element el, int level, Document doc, List<Document> result,
                               String[] headings, StringBuilder buffer) {
        String title = normalizeTitle(el.text());
        if (title.isEmpty()) {
            return;
        }
        flushBuffer(buffer, doc, result, headingPathOf(headings));
        setHeading(headings, level, title);
        buffer.append(title).append('\n');   // 标题文字保留正文（BM25/向量化可检索）
    }

    /** 文本逐行扫描：Markdown 标题行触发冲刷 + 标题栈更新，其余行入缓冲 */
    private void appendTextLines(StringBuilder buffer, Document doc, List<Document> result,
                                 String[] headings, String text) {
        for (String line : text.split("\n", -1)) {
            Matcher m = MARKDOWN_HEADING.matcher(line.stripTrailing());
            if (m.matches()) {
                String title = normalizeTitle(m.group(2));
                if (!title.isEmpty()) {
                    flushBuffer(buffer, doc, result, headingPathOf(headings));
                    setHeading(headings, m.group(1).length(), title);
                    buffer.append(title).append('\n');   // 标题文字保留正文首部
                    continue;
                }
            }
            buffer.append(line).append('\n');
        }
    }

    /** 标题文字归一：NBSP 等不可见字符视为空白（{@code String.isBlank()} 不覆盖 NBSP），空白标题返回空串 */
    private static String normalizeTitle(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace('\u00a0', ' ').strip();
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
        meta.put(Constants.Retrieval.META_CHUNK_TYPE, type.name());
        meta.put(ORIGINAL_HTML_KEY, html);
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
