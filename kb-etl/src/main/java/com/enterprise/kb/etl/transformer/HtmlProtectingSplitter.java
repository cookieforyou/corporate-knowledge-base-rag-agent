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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 结构保护式切分器（设计文档 9.2，任务 2.3；冲刺簇④ A4 heading 路径跟踪）
 *
 * <p>表格/图片作为一等公民保护：
 * <ul>
 *   <li>{@code <table>} 块 → 独立 Chunk（chunk_type=TABLE），原文 HTML 存
 *       original_html（落库 kb_chunk.original_content；为「保护块原文」——含前置标题行，
 *       与 chunk 正文对齐，9.2 v2.27），供前端回显与结构保真；</li>
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
 *
 * <p><b>修复批 2（2026-10-04，9.2 v2.23）——递归 DOM 遍历 + 块级边界</b>：
 * AST 路径由「body 直接子节点」改为<b>递归遍历</b>——嵌套 {@code <div>}/{@code <section>}
 * 内的 {@code h1}~{@code h6} 与 {@code table}/{@code img} 同样识别（原实现下
 * {@code <div><h1>…</h1><table>…</table></div>} 的标题与表格一并退化纯文本、
 * {@code <p><img/></p>} 与深层 {@code div>section>figure>img} 的图片零 chunk 丢失）。
 * 配套三处纪律：
 * <ol>
 *   <li><b>块级边界</b>：块级元素前后补行边界（{@code <br>} 补换行）——段落/列表项/换行
 *       不再被 {@code el.text()} 合并成一行（影响 BM25 与向量语义）；</li>
 *   <li><b>字面内容</b>：{@code pre}/{@code code} 原文入缓冲且不做标题扫描
 *       （原实现靠「整段 {@code el.text()}」恰好不出错，递归后必须显式保留——
 *       否则代码里的 `# 注释` 会成为假标题）；</li>
 *   <li><b>跳过标签</b>：{@code script}/{@code style}/{@code noscript}/{@code title} 等不入正文
 *       （同理，原实现靠不递归才没有噪声）；文本节点换行语义改为「行间补换行、行尾不补」，
 *       避免行内元素处凭空断行。</li>
 * </ol>
 *
 * <p><b>修复批 3（2026-10-04，9.2 v2.24，评审热修）</b>：
 * <ol>
 *   <li><b>注释区间同口径</b>：Markdown 标题判据与路径 1 行扫描一并排除 HTML 注释区间
 *       （原实现只对「结构标签」判据剥注释，多行注释内的 {@code # 行} 会被当真标题冲刷并进
 *       heading_path——与「判据只看围栏与注释之外」的声明矛盾）；未闭合注释延伸至文末，
 *       围栏行不参与注释状态机（围栏内的 {@code <!--} 不会把其后的真实标题屏蔽）；</li>
 *   <li><b>小表格行边界</b>：小表格退化分支补 {@link #lineBreak}——行内上下文
 *       （{@code <span>a</span><table>…</table>}）不再与表格文本粘连；</li>
 *   <li><b>行内上下文不判标题</b>：{@code walk} 传播块级上下文标志，行内元素
 *       （{@code <b>}/{@code <span>} 等）内的多行文本不再把行首 {@code # } 当 Markdown 标题；
 *       块级元素与顶层文本节点行为不变（DocMind 正文为顶层 Markdown 文本，不受影响）；</li>
 *   <li><b>微整理</b>：{@code flushBuffer} 单次 {@code toString()}（去重复副本）；
 *       行首判据统一由 {@link #appendLine} 内部执行（去调用侧重复表达式）。</li>
 * </ol>
 *
 * <p><b>修复批 4（2026-10-04，9.2 v2.25，短文本静默丢弃根治）</b>：{@code TokenTextSplitter}
 * 按 {@code length() > minChunkLengthToEmbed(10)} <b>严格判据静默丢弃</b>过短文本——容器标题
 * （标题后紧跟更深标题、章节无正文）≤10 字符时整个标题文字消失（仅存于后续 chunk 的
 * heading_path，BM25/向量化均检索不到）。治法：{@link #flushBuffer} 返回未成 chunk 的残余文本，
 * 「残余随其后继内容落位」——后继为标题则回填缓冲进入子章节 chunk、后继为保护块则前置到该
 * 保护块正文（{@code original_html} 同记该原文）、文末无后继则兜底自成 chunk。
 * 实测：6 篇语料 chunk 数全部不变（无索引位移），仅 2 篇受影响文档的 5 个 chunk 正文变化。
 *
 * <p><b>修复批 5（2026-10-04，9.2 v2.26，布局保真微调）</b>：容器标题归位（批4）时该标题与其后子标题/
 * 保护块之间的**空行被 {@code strip()} 吃掉**，chunk 正文与源文档布局不一致——治法 = 残余文本
 * **原样衔接**（{@link #carryInto} 不 strip，仅补行边界；{@link #protectedChunk} 前缀仅去前导空白、
 * 保留其后空行）。正文变化仅限受影响的 5 个 chunk（无 chunk 数变化）。
 *
 * <p><b>修复批 6（2026-10-04，9.2 v2.27，保护块原文对齐）</b>：批4/批5 让无正文短标题以
 * 前缀形式进入保护块 chunk 正文（可检索），但 {@code original_html} 仍只记纯 HTML，落库后
 * {@code content} 有标题而 {@code original_content} 没有（用户侧复入库观测发现）——治法 =
 * {@code original_html} 同记「前缀 + HTML」原文：全类型 chunk 的 {@code original_content}
 * 与 {@code content} 只差语境增强前缀（未增强时同值），语义单一。
 *
 * <p><b>修复批 7（2026-10-04，9.2 v2.30，AST 视图预处理）</b>：用户侧提问「含 {@code <table>} 的
 * Markdown 为何能走 JSoup、JSoup 不转换能处理 Markdown 吗」复核时发现——JSoup 只作结构解析器
 * （Markdown 标记对它是惰性文本），但**凡是交给它的文本就受 HTML 词法约束**，由此暴露两类真实缺陷：
 * <ol>
 *   <li><b>未闭合结构标签吞并后文</b>：{@code <table>} 缺闭合 ⇒ 其后全文落入表格元素，产出**一个**
 *       1871 字符的 TABLE chunk（实测 28 → 19 chunk，尾部章节标题失去语义）；{@code <h2>} 缺闭合
 *       ⇒ 其后正文变成标题文字，heading_path 污染成含正文的长串。治法 = {@link #balanceStructure}
 *       在解析前补足落单的标题标签与表格结构标签；</li>
 *   <li><b>字面尖括号被当标签吞掉</b>：行内 code 内的 {@code <svc>}/{@code List<String>}/{@code <v1.2>}
 *       标签名消失（实测 {@code Optional<List<String>>} → {@code Optional> }）。治法 =
 *       {@link #shieldInlineCode} 按长度配对反引号并转义区间内的 HTML 元字符（与围栏屏蔽同一机制）。</li>
 * </ol>
 * 三项变换（含 v2.22 围栏屏蔽）统一收在 {@link #astView}，均只作用于围栏与注释之外；无变换命中时
 * 输出与原实现逐字节一致——**真实语料 6 篇 chunk 计数与 ID 零漂移**。
 *
 * <p><b>围栏规则声明（有意简化）</b>：围栏按「≤3 空格缩进 + 3 个以上同字符（``` / ~~~）」识别，
 * 不校验 CommonMark 的 info string 约束（如反引号围栏的 info string 不得含反引号）——
 * 该差异仅影响病态输入（{@code ``` a`b} 一行），不引入结构或内容丢失。
 *
 * <p><b>配平规则声明（有意简化，9.2 v2.30）</b>：表格结构标签按「整篇计数缺口 + 最后一个结构闭合行」
 * 定位补足点，不重建完整标签栈——覆盖「单表缺一个闭合标签」（LLM 产出的表格 HTML 与手写 Markdown
 * 嵌 HTML 的常见形态）；多表且**非末表**未闭合、或表格 HTML 被截断在行中的病态输入不在此列
 * （补在此处会张冠李戴，故两条不猜原则直接原样返回）。结构标签之外的未闭合元素
 * （{@code <div>}/{@code <p>}）无需处理：递归遍历照常取其文本，只有行边界差异。
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
     * 表格结构标签 token（9.2 v2.30 配平用）：开/闭标记 + 标签名，严格边界
     * （{@code <tableau>} / {@code </tableau>} 不计入）。
     */
    private static final Pattern TABLE_STRUCTURE_TOKEN =
        Pattern.compile("<(/?)(tfoot|tbody|thead|table)(?=[\\s/>])", Pattern.CASE_INSENSITIVE);

    /** 表格结构的「闭合边界行」判据：行内含任一结构闭合标签（单元格/行闭合同样是结构边界） */
    private static final Pattern TABLE_STRUCTURE_CLOSE =
        Pattern.compile("</(tbody|thead|tfoot|tr|td|th|table)>", Pattern.CASE_INSENSITIVE);

    /** 表格结构标签配平顺序（内层 → 外层）：缺口补在当前表最后一个结构闭合行之后 */
    private static final List<String> TABLE_STRUCTURE_TAGS = List.of("tfoot", "tbody", "thead", "table");

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

    /** 不入正文的标签（脚本/样式/元信息）：递归遍历下须显式跳过，否则其文本会进 chunk */
    private static final Set<String> SKIP_TAGS = Set.of("script", "style", "noscript", "head", "title", "template");

    /** 字面内容标签：原文入缓冲，不做标题扫描（围栏转义载体 pre + 行内 code） */
    private static final Set<String> LITERAL_TAGS = Set.of("pre", "code");

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
            return splitByMarkdownHeadings(doc, lines, scan.inFence, scan.inComment);
        }
        return splitWithTracking(doc, astView(lines, scan.inFence));
    }

    // ── 结构判据：一次行扫描得出围栏区间 + 三判据（9.2 v2.22） ──

    /**
     * 行扫描结果：围栏与注释区间标记 + 保护标签 / HTML 标题 / Markdown 标题三判据。
     * 判据口径统一——围栏与注释之外才参与判定（新增结构判据时在此扩展区间标记）。
     */
    private record Scan(boolean[] inFence, boolean[] inComment,
                        boolean hasProtected, boolean hasHtmlHeading, boolean hasMarkdownHeading) {}

    private static Scan scan(String[] lines) {
        boolean[] inFence = new boolean[lines.length];
        StringBuilder outside = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            Matcher open = FENCE.matcher(lines[i]);
            if (!open.matches()) {
                outside.append(lines[i]).append('\n');
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
        // 注释区间（与标签判据同口径）：注释内的 Markdown 标题不得冲刷章节、不得污染 heading_path
        boolean[] inComment = commentLines(lines, inFence);
        boolean hasMarkdownHeading = false;
        for (int j = 0; j < lines.length && !hasMarkdownHeading; j++) {
            hasMarkdownHeading = !inFence[j] && !inComment[j]
                && MARKDOWN_HEADING.matcher(lines[j].stripTrailing()).matches();
        }
        // 标签判据：剔除 HTML 注释（注释内的结构标签不触发 AST 路径）
        String probe = HTML_COMMENT.matcher(outside.toString()).replaceAll(" ");
        return new Scan(inFence, inComment, PROTECTED_TAG.matcher(probe).find(),
            HTML_HEADING_TAG.matcher(probe).find(), hasMarkdownHeading);
    }

    /**
     * 逐行注释屏蔽标记（9.2 v2.24）：**仅标记「行首已处于 `<!-- … -->` 之内」的行**。
     *
     * <p>ATX 标题的标记必为行首非空白字符，故「行首在注释内」等价于「标题标记在注释内」；
     * 行尾注释（如 {@code # 标题 <!-- 注 -->}）不屏蔽——标题语义保留，避免过度屏蔽。
     * 未闭合注释延伸至文末；围栏行不参与状态机（围栏内的 {@code <!--} 不改变状态，
     * 否则会把围栏之后的真实标题一并屏蔽）。
     */
    private static boolean[] commentLines(String[] lines, boolean[] inFence) {
        boolean[] inComment = new boolean[lines.length];
        boolean inside = false;
        for (int i = 0; i < lines.length; i++) {
            if (inFence[i]) {
                continue;
            }
            inComment[i] = inside;
            String line = lines[i];
            int pos = 0;
            while (pos < line.length()) {
                if (inside) {
                    int close = line.indexOf("-->", pos);
                    if (close < 0) {
                        break;                       // 注释延续至后续行
                    }
                    inside = false;
                    pos = close + 3;
                } else {
                    int start = line.indexOf("<!--", pos);
                    if (start < 0) {
                        break;
                    }
                    inside = true;
                    pos = start + 4;
                }
            }
        }
        return inComment;
    }

    // ── AST 解析视图（9.2 v2.22 围栏屏蔽；v2.30 追加行内 code 屏蔽 + 结构标签配平） ──

    /**
     * AST 解析视图：把源文本改写成「JSoup 只看见该看见的东西」的形态，三步逐行变换：
     * <ol>
     *   <li><b>围栏屏蔽（v2.22）</b>：围栏区间改写为 {@code <pre>转义原文</pre>}——围栏内容对 JSoup
     *       不可见（不会变成真标题/真表格），由 {@code pre} 字面分支原样回到 chunk。
     *       用 HTML 转义而非哨兵占位：控制字符会被 HTML 解析器丢弃，哨兵方案实测污染语料正文；</li>
     *   <li><b>行内 code 屏蔽（v2.30）</b>：成对反引号内的 HTML 元字符转义——{@code `<svc>.<ns>`}、
     *       {@code `List<String>`} 这类字面尖括号不再被当作未知标签吞掉（详见
     *       {@link #shieldInlineCode}）；</li>
     *   <li><b>结构标签配平（v2.30）</b>：未闭合的 {@code <h1>..<h6>} / 表格结构标签补上闭合标签
     *       ——否则 HTML 解析器按「元素延伸到父级结束」把它们之后的全文吞进标题或表格元素
     *       （详见 {@link #balanceStructure}）。</li>
     * </ol>
     * 三步均只作用于围栏与注释之外的文本；无变换命中时输出与原实现逐字节一致
     * （真实语料 6 篇零漂移）。
     */
    private static String astView(String[] lines, boolean[] inFence) {
        String[] view = new String[lines.length];
        boolean[] viewFence = new boolean[lines.length];
        int n = 0;
        int i = 0;
        while (i < lines.length) {
            if (!inFence[i]) {
                view[n] = shieldInlineCode(lines[i]);
                viewFence[n] = false;
                n++;
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
            view[n] = "<pre>" + escapeHtml(fenceText.toString()) + "</pre>";
            viewFence[n] = true;
            n++;
            i = end + 1;
        }
        String[] viewLines = Arrays.copyOf(view, n);
        boolean[] fenceLines = Arrays.copyOf(viewFence, n);
        balanceStructure(viewLines, fenceLines);
        return String.join("\n", viewLines) + "\n";
    }

    /**
     * 行内 code 屏蔽（9.2 v2.30）：成对反引号区间内的 {@code &} {@code <} {@code >} 转义为实体
     * （JSoup 解析时还原为原文）——AST 路径下字面尖括号（{@code `<svc>.<ns>.svc.cluster.local`}、
     * {@code `Optional<List<String>>`}、{@code `<v1.2>`}）原为 HTML 解析器的未知标签，
     * 标签名连同紧随的字符一并消失（实测 {@code List<String>} → {@code >}）。
     *
     * <p><b>配对纪律</b>：按反引号「长度」两两配对（`` ``a`b`` `` 这类含反引号的行内 code 也能正确
     * 闭合）；**同长度出现落单反引号时整行不屏蔽**——否则落单标记会把其后的真标签
     * （如行内的 {@code <table>}）一并转义，保护反而失效。行级状态，不跨行延续。
     */
    private static String shieldInlineCode(String line) {
        if (line.indexOf('`') < 0) {
            return line;
        }
        List<int[]> runs = new ArrayList<>();                 // {起始下标, 长度}
        int i = 0;
        while (i < line.length()) {
            if (line.charAt(i) == '`') {
                int run = 1;
                while (i + run < line.length() && line.charAt(i + run) == '`') {
                    run++;
                }
                runs.add(new int[] {i, run});
                i += run;
            } else {
                i++;
            }
        }
        Map<Integer, Deque<Integer>> pending = new HashMap<>();
        boolean[] shielded = new boolean[line.length()];
        for (int r = 0; r < runs.size(); r++) {
            int length = runs.get(r)[1];
            Deque<Integer> bucket = pending.computeIfAbsent(length, _ -> new ArrayDeque<>());
            if (bucket.isEmpty()) {
                bucket.addLast(r);
            } else {
                Arrays.fill(shielded, runs.get(bucket.removeLast())[0], runs.get(r)[0] + length, true);
            }
        }
        if (pending.values().stream().anyMatch(bucket -> !bucket.isEmpty())) {
            return line;                                      // 落单反引号：保守起见整行不屏蔽
        }
        StringBuilder out = new StringBuilder(line.length() + 16);
        int pos = 0;
        while (pos < line.length()) {
            if (!shielded[pos]) {
                out.append(line.charAt(pos));
                pos++;
                continue;
            }
            int end = pos;
            while (end < line.length() && shielded[end]) {
                end++;
            }
            out.append(escapeHtml(line.substring(pos, end)));
            pos = end;
        }
        return out.toString();
    }

    /**
     * 结构标签配平（9.2 v2.30）：HTML 解析器对未闭合元素按「延伸到父级结束」处理，把其后全文
     * 塞进该元素——{@code <h2>标题} 缺闭合 ⇒ 其后正文变成**标题文字**（heading_path 被污染成
     * 含正文的长串、章节不再分块）；{@code <table>} 缺闭合 ⇒ 其后全文被吞进**一个** TABLE chunk
     * （实测 28 → 19 chunk、单块 1871 字符 ≈ 2.3× chunkSize，尾部 Markdown 标题一并失去语义）。
     * 治法 = 交给 JSoup 之前把缺口补上（围栏行与注释行不参与；单行注释内容先剥离）。
     */
    private static void balanceStructure(String[] view, boolean[] viewFence) {
        boolean[] viewComment = commentLines(view, viewFence);
        balanceHeadings(view, viewFence, viewComment);
        balanceTableStructure(view, viewFence, viewComment);
    }

    /**
     * 标题标签配平：整篇按出现顺序 LIFO 配对后**仍落单的 `<hN>`**，在其所在行行尾补 `</hN>`
     * ——HTML 标题的文字天然在同一行，落单即为「吞掉后文」的病态形态。
     * 跨行书写的合法标题（{@code <h2>\n标题\n</h2>}）配对完整，不受影响。
     */
    private static void balanceHeadings(String[] view, boolean[] viewFence, boolean[] viewComment) {
        for (int level = 1; level <= 6; level++) {
            Pattern token = Pattern.compile("</?h" + level + "(?=[\\s/>])", Pattern.CASE_INSENSITIVE);
            String closer = "</h" + level + ">";
            Deque<Integer> unmatched = new ArrayDeque<>();
            for (int i = 0; i < view.length; i++) {
                if (viewFence[i] || viewComment[i]) {
                    continue;
                }
                Matcher m = token.matcher(stripInlineComments(view[i]));
                while (m.find()) {
                    if (m.group().startsWith("</")) {
                        unmatched.pollLast();                 // 有配对：出栈（落单闭合标签忽略）
                    } else {
                        unmatched.addLast(i);
                    }
                }
            }
            for (int line : unmatched) {
                view[line] = appendAtLineEnd(view[line], closer);
            }
        }
    }

    /**
     * 表格结构标签配平：整篇计数有缺口（{@code <table>} 多于 {@code </table>} 等）时，把缺的闭合
     * 标签按**内层→外层**（tfoot/tbody/thead/table）补在「最后一个结构闭合行」之后——即当前表
     * 最后一个 {@code </td>}/{@code </th>}/{@code </tr>}/{@code </tbody>} 行处，表格之后的内容
     * 随即回到正常文本流（重新参与标题识别与分块）。无任何结构闭合行可定位时（单行表整行缺闭合）
     * 退回该表起始行行尾——仍好于让它吞掉全文。
     *
     * <p>两条不猜原则：最后一个 {@code <table>} 出现在边界行之后（缺口在末表之前，补在此处会
     * 张冠李戴）、无缺口——任一成立即原样返回。{@code </tr>}/{@code </td>} 缺失不必补足计数：
     * 解析器遇 {@code </tbody>}/{@code </table>} 自动闭合单元格与行，实测无吞并。
     */
    private static void balanceTableStructure(String[] view, boolean[] viewFence, boolean[] viewComment) {
        Map<String, Integer> missing = new LinkedHashMap<>();
        int boundary = -1;
        int lastTableOpen = -1;
        for (int i = 0; i < view.length; i++) {
            if (viewFence[i] || viewComment[i]) {
                continue;
            }
            String line = stripInlineComments(view[i]);
            if (TABLE_STRUCTURE_CLOSE.matcher(line).find()) {
                boundary = i;
            }
            Matcher m = TABLE_STRUCTURE_TOKEN.matcher(line);
            while (m.find()) {
                String tag = m.group(2).toLowerCase();
                if (m.group(1).isEmpty()) {
                    missing.merge(tag, 1, Integer::sum);
                    if (tag.equals("table")) {
                        lastTableOpen = i;
                    }
                } else {
                    missing.merge(tag, -1, Integer::sum);
                }
            }
        }
        StringBuilder closers = new StringBuilder();
        for (String tag : TABLE_STRUCTURE_TAGS) {
            int gap = missing.getOrDefault(tag, 0);
            if (gap > 0) {
                closers.repeat("</" + tag + ">", gap);
            }
        }
        if (boundary < 0) {
            boundary = lastTableOpen;                 // 无任何结构闭合行（单行表整行缺闭合）：退回表起始行行尾
        }
        if (closers.isEmpty() || boundary < 0 || lastTableOpen > boundary) {
            return;
        }
        view[boundary] = appendAtLineEnd(view[boundary], closers.toString());
    }

    /** 单行 HTML 注释剥离（计数口径与结构判据一致：注释内的标签不算标签） */
    private static String stripInlineComments(String line) {
        return !line.contains("<!--") ? line : HTML_COMMENT.matcher(line).replaceAll(" ");
    }

    /**
     * 行尾补标签；行内存在**未闭合注释起始**时插在其之前——插进注释里的标签解析器看不见，
     * 补了等于没补（{@code <h2>标题 <!-- 说明} 跨行注释形态）。
     */
    private static String appendAtLineEnd(String line, String tags) {
        int cut = unterminatedCommentStart(line);
        return cut < 0 ? line + tags : line.substring(0, cut) + tags + line.substring(cut);
    }

    /** 行内第一个未闭合 {@code <!--} 的下标（无则 -1），与 {@link #commentLines} 同口径扫描 */
    private static int unterminatedCommentStart(String line) {
        int pos = 0;
        while (pos < line.length()) {
            int start = line.indexOf("<!--", pos);
            if (start < 0) {
                return -1;
            }
            int close = line.indexOf("-->", start + 4);
            if (close < 0) {
                return start;
            }
            pos = close + 3;
        }
        return -1;
    }

    /** 仅转义 HTML 元字符（JSoup 解析时还原为原文） */
    private static String escapeHtml(String raw) {
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ── 路径 1：仅 Markdown 标题的文档（逐行扫描，围栏区间不判标题） ──

    /**
     * 路径 1：仅 Markdown 标题的文档逐行扫描（{@code trailingNewline=true} 与初版逐字一致）。
     * 围栏与注释区间均不判标题（注释文本仍原样入正文，仅不做标题语义）。
     */
    private List<Document> splitByMarkdownHeadings(Document doc, String[] lines, boolean[] inFence, boolean[] inComment) {
        List<Document> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        String[] headings = new String[7];
        for (int i = 0; i < lines.length; i++) {
            appendLine(buffer, doc, result, headings, lines[i], !inFence[i] && !inComment[i], true);
        }
        String tail = flushBuffer(buffer, doc, result, headingPathOf(headings));
        if (tail != null) {
            result.add(leftoverChunk(doc, tail, headingPathOf(headings)));
        }
        return result;
    }

    /**
     * 路径 2：结构感知切分——JSoup 递归遍历 DOM（9.2 v2.23），文本按行扫描 Markdown 标题，
     * 标题栈随遇随更新；标题变更即冲刷缓冲（chunk 与章节对齐），
     * 保护块（TABLE/IMAGE）独立成 chunk 并携带当前 heading_path，块级元素补行边界。
     */
    private List<Document> splitWithTracking(Document doc, String text) {
        List<Document> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        String[] headings = new String[7];   // 下标 1..6 = h1..h6 当前标题

        for (Node node : Jsoup.parseBodyFragment(text).body().childNodes()) {
            walk(node, doc, result, headings, buffer, true);
        }
        String tail = flushBuffer(buffer, doc, result, headingPathOf(headings));
        if (tail != null) {
            result.add(leftoverChunk(doc, tail, headingPathOf(headings)));
        }
        return result;
    }

    /**
     * 递归遍历（9.2 v2.23 修复批2）：嵌套层级内的标题与 table/img 同样识别——原实现只看
     * body 直接子节点，`<div><h1>…</h1><table>…</table></div>` 的标题与表格一并退化纯文本、
     * `<p><img/></p>` 与深层 `div&gt;section&gt;figure&gt;img` 的图片零 chunk 丢失。
     *
     * <p>五类分派：文本节点逐行扫描；h1~h6 空标题守卫后冲刷入栈；table/img 保护块独立成 chunk
     * （小表格退化文本）；`<br>` 补换行；其余元素在块级前后补行边界后递归子节点——其中
     * {@code pre}/{@code code} 为字面内容（原样入缓冲、不做标题扫描，否则代码里的 `# 注释`
     * 会成为假标题），{@code script}/{@code style} 等不入正文。
     */
    private void walk(Node node, Document doc, List<Document> result, String[] headings, StringBuilder buffer,
                      boolean allowHeading) {
        if (node instanceof TextNode textNode) {
            appendTextLines(buffer, doc, result, headings, textNode.getWholeText(), allowHeading);
            return;
        }
        if (!(node instanceof Element el)) {
            return;                                   // Comment / DataNode：不入正文
        }
        String tag = el.tagName().toLowerCase();
        if (SKIP_TAGS.contains(tag)) {
            return;
        }
        int level = headingLevelOf(tag);
        if (level > 0) {
            onHtmlHeading(el, level, doc, result, headings, buffer);
            return;                                   // 标题内部不再下探
        }
        switch (tag) {
            case "table" -> {
                if (el.text().length() < MIN_TABLE_CHARS) {
                    lineBreak(buffer);                        // 行内上下文（如 <span>a</span><table>）不得与表格文本粘连
                    buffer.append(el.text()).append('\n');   // 小表格退化纯文本
                } else {
                    result.add(protectedChunk(doc, flushBuffer(buffer, doc, result, headingPathOf(headings)),
                        el.outerHtml(), ChunkType.TABLE, headingPathOf(headings)));
                }
            }
            case "img" -> result.add(protectedChunk(doc, flushBuffer(buffer, doc, result, headingPathOf(headings)), el.outerHtml(), ChunkType.IMAGE, headingPathOf(headings)));
            case "br" -> lineBreak(buffer);
            default -> {
                if (LITERAL_TAGS.contains(tag)) {          // pre / code：字面内容，不判标题
                    boolean literalBlock = el.tag().isBlock();
                    if (literalBlock) {
                        lineBreak(buffer);
                    }
                    buffer.append(el.wholeText());
                    if (literalBlock) {
                        buffer.append('\n');
                    }
                    return;
                }
                boolean block = el.tag().isBlock();
                if (block) {
                    lineBreak(buffer);
                }
                for (Node child : el.childNodes()) {
                    // 行内元素内的多行文本不判 Markdown 标题（`<b>前缀\n# 行</b>` 不产生标题语义）
                    walk(child, doc, result, headings, buffer, block && allowHeading);
                }
                if (block) {
                    lineBreak(buffer);
                }
            }
        }
    }

    /** HTML 标题：空标题（含 NBSP 伪标题）不冲刷、不入栈——避免无意义章节边界（9.2 v2.22） */
    private void onHtmlHeading(Element el, int level, Document doc, List<Document> result,
                               String[] headings, StringBuilder buffer) {
        String title = normalizeTitle(el.text());
        if (title.isEmpty()) {
            return;
        }
        carryInto(buffer, flushBuffer(buffer, doc, result, headingPathOf(headings)));
        setHeading(headings, level, title);
        buffer.append(title).append('\n');   // 标题文字保留正文（BM25/向量化可检索）
    }

    /**
     * AST 文本节点逐行扫描（9.2 v2.23）：行间换行原样保留、行尾不补换行（块级元素的行边界
     * 由 {@link #lineBreak} 负责）——避免行内元素（`<b>`/`<code>`/`<span>`）处凭空断行。
     * 标题仅在行首成立：节点内非首行，或缓冲处于行边界。
     */
    private void appendTextLines(StringBuilder buffer, Document doc, List<Document> result,
                                 String[] headings, String text, boolean allowHeading) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                buffer.append('\n');
            }
            appendLine(buffer, doc, result, headings, lines[i], allowHeading, false);   // 行首判据在 appendLine 内统一执行
        }
    }

    /**
     * 单行处理：允许判标题时按 ATX 规则识别（冲刷 + 入栈 + 标题文字留正文），否则原文入缓冲。
     *
     * @param trailingNewline 整篇 Markdown 行扫描逐行补换行（与初版行为逐字一致）；
     *                        AST 文本节点不补（行间换行由 {@link #appendTextLines} 补）
     */
    private void appendLine(StringBuilder buffer, Document doc, List<Document> result, String[] headings,
                            String line, boolean mayBeHeading, boolean trailingNewline) {
        if (mayBeHeading && bufferAtLineStart(buffer)) {
            Matcher m = MARKDOWN_HEADING.matcher(line.stripTrailing());
            if (m.matches()) {
                String title = normalizeTitle(m.group(2));
                if (!title.isEmpty()) {
                    carryInto(buffer, flushBuffer(buffer, doc, result, headingPathOf(headings)));
                    setHeading(headings, m.group(1).length(), title);
                    buffer.append(title);
                    if (trailingNewline) {
                        buffer.append('\n');
                    }
                    return;
                }
            }
        }
        buffer.append(line);
        if (trailingNewline) {
            buffer.append('\n');
        }
    }

    /** 缓冲是否处于行边界（空缓冲或末尾为换行）——Markdown 标题只能在行首成立 */
    private static boolean bufferAtLineStart(StringBuilder buffer) {
        return buffer.isEmpty() || buffer.charAt(buffer.length() - 1) == '\n';
    }

    /** 补行边界（块级元素与 `<br>` 前后）：已在行首则不重复补 */
    private static void lineBreak(StringBuilder buffer) {
        if (!bufferAtLineStart(buffer)) {
            buffer.append('\n');
        }
    }

    /**
     * 标题文字归一（9.2 v2.24）：剔除行内 HTML 注释（与判据同口径——注释不是标题语义的一部分）、
     * NBSP 等不可见字符按空白处理（{@code String.isBlank()} 不覆盖 NBSP），空白标题返回空串。
     * 归一只作用于标题文字；正文中的注释文本仍原样保留。
     */
    private static String normalizeTitle(String raw) {
        if (raw == null) {
            return "";
        }
        return HTML_COMMENT.matcher(raw).replaceAll(" ").replace('\u00a0', ' ').strip();
    }

    /** 冲刷累积文本：携带当前 heading_path 经 TokenTextSplitter 常规切分后追加 */
    private String flushBuffer(StringBuilder buffer, Document doc, List<Document> result, String headingPath) {
        String text = buffer.toString();
        buffer.setLength(0);
        if (text.isBlank()) {
            return null;
        }
        Map<String, Object> meta = new HashMap<>(doc.getMetadata());
        if (headingPath != null && !headingPath.isBlank()) {
            meta.put(HEADING_PATH_KEY, headingPath);
        }
        Document textDoc = Document.builder()
            .text(text)
            .metadata(meta)
            .build();
        List<Document> chunks = textSplitter.apply(List.of(textDoc));
        if (chunks.isEmpty()) {
            return text;                 // 过短（≤minChunkLengthToEmbed）未成 chunk：交调用方随后继内容落位
        }
        result.addAll(chunks);
        return null;
    }

    /**
     * 残余文本随其后继内容落位（9.2 v2.25；v2.26 布局保真）：后继是标题（容器标题，章节无正文）
     * → **原样**回填缓冲，标题文字与其后的空行（原文档「容器标题/空行/子标题」三段布局）一并保留，
     * 再进入子章节 chunk 正文；后继是保护块 → 前置到该保护块正文（见 {@link #protectedChunk}）。
     */
    private static void carryInto(StringBuilder buffer, String leftover) {
        if (leftover != null) {
            buffer.append(leftover);        // 原样衔接：不 strip——容器标题与子标题间的空行不得丢
            lineBreak(buffer);              // 残余无尾换行时补行边界，保证与后继标题分行
        }
    }

    /** 文末兜底：残余文本已无后继内容可落位，直接成 chunk（保内容不丢，chunk_type 缺省 TEXT） */
    private static Document leftoverChunk(Document doc, String text, String headingPath) {
        Map<String, Object> meta = new HashMap<>(doc.getMetadata());
        if (headingPath != null && !headingPath.isBlank()) {
            meta.put(HEADING_PATH_KEY, headingPath);
        }
        return Document.builder().text(text.strip()).metadata(meta).build();
    }

    /**
     * 保护块独立成 Chunk：chunk_type + original_html + heading_path 元数据。
     * {@code prefix}（当前章节的残余标题文字，可为 null）与保护块 HTML 共同构成 chunk 正文，
     * 且**原样保留其后的空行**（与源文档「标题/空行/表格」布局一致，9.2 v2.26）；
     * {@code original_html} 记同一份原文（含前置标题行）——落库 {@code original_content} 与
     * {@code content} 对齐（9.2 v2.27），保护块 HTML 原样保留在其中，结构保真不受影响。
     */
    private static Document protectedChunk(Document doc, String prefix, String html, ChunkType type, String headingPath) {
        Map<String, Object> meta = new HashMap<>(doc.getMetadata());
        meta.put(Constants.Retrieval.META_CHUNK_TYPE, type.name());
        if (headingPath != null && !headingPath.isBlank()) {
            meta.put(HEADING_PATH_KEY, headingPath);
        }
        String text = html;
        if (prefix != null && !prefix.isBlank()) {
            String head = prefix.stripLeading();     // 去前导空白（与前一块的间隔），保留标题与其后内容的原始空行
            text = head.endsWith("\n") ? head + html : head + "\n" + html;
        }
        // 原文与正文对齐（9.2 v2.27）：original_html 记「保护块原文」——含前置标题行（若该章节为无正文
        // 短标题形态），落库 kb_chunk.original_content 后与 content 只差语境增强前缀，不再出现
        // 「content 有标题而 original_content 没有」的错位；保护块 HTML 原样保留在其中。
        meta.put(ORIGINAL_HTML_KEY, text);
        return Document.builder().text(text).metadata(meta).build();
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
