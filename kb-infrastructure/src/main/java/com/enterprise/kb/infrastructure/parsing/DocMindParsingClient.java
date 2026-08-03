package com.enterprise.kb.infrastructure.parsing;

import com.aliyun.docmind_api20220711.Client;
import com.aliyun.docmind_api20220711.models.GetDocParserResultRequest;
import com.aliyun.docmind_api20220711.models.GetDocParserResultResponse;
import com.aliyun.docmind_api20220711.models.QueryDocParserStatusRequest;
import com.aliyun.docmind_api20220711.models.QueryDocParserStatusResponse;
import com.aliyun.docmind_api20220711.models.SubmitDocParserJobAdvanceRequest;
import com.aliyun.docmind_api20220711.models.SubmitDocParserJobResponse;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

/**
 * DocMind 文档解析大模型版客户端（设计文档 9.1 主选，任务 2.2）
 *
 * <p>异步 API 三件套（SDK docmind_api20220711 源码核验）：
 * <ol>
 *   <li>{@code submitDocParserJobAdvance} —— 文件流上传（无需公网 URL），返回 jobId；</li>
 *   <li>{@code queryDocParserStatus} —— 轮询至 status=success（间隔/上限可配）；</li>
 *   <li>{@code getDocParserResult} —— layoutNum 分页拉取，合并 layouts。</li>
 * </ol>
 *
 * <p>结果载荷为无类型 Map（SDK 未建模 Data 内层），按防御式多键回退提取。
 * 字段名以官方文档（文档解析大模型版 GetDocParserResult）为准：
 * 正文取 {@code markdownContent}→{@code text}；表格 HTML 需提交时开启
 * {@code OutputHtmlTable}（须同时开启 LlmEnhancement），HTML 内容存放在
 * 表格版面块的 {@code llmResult} 字段（可能包裹 ``` 代码围栏，提取时剥离），
 * 保留 {@code <table>} 供 2.3 HtmlProtectingSplitter 保护为 TABLE Chunk。
 *
 * <p><b>2026-08-03 修复</b>：① 正文曾读取不存在的 {@code markdown} 键（实际为
 * {@code markdownContent}），表格/正文结构丢失、全部退化为 text 纯文本；
 * ② 未请求 {@code OutputHtmlTable} 且误从 {@code html} 键取表格 HTML——大模型版
 * 该键不存在，导致 16 表格的文档切分后 0 个 TABLE Chunk。
 *
 * <p>鉴权：RAM AccessKey 经环境变量注入（{@link ParsingProperties}）；Client 懒初始化，
 * 缺 Key 环境（kb-eval 等）Bean 可创建，parse() 时才抛 {@link ParsingException}
 * 由路由层降级，不污染无关上下文启动。
 */
@Slf4j
@Component
public class DocMindParsingClient implements ParsingServiceClient {

    private static final int LAYOUT_STEP = 100;

    private final ParsingProperties.Docmind props;
    private volatile Client client;

    public DocMindParsingClient(ParsingProperties properties) {
        this.props = properties.getDocmind();
    }

    @Override
    public String providerName() {
        return "docmind";
    }

    @Override
    public ParsingResult parse(byte[] content, String fileName) {
        try {
            String jobId = submit(content, fileName);
            awaitCompletion(jobId);
            return fetchResult(jobId);
        } catch (ParsingException e) {
            throw e;
        } catch (Exception e) {
            throw new ParsingException("DocMind 解析失败: " + e.getMessage(), e);
        }
    }

    // ── 1. 提交（文件流上传，advance 请求内部经凭证中转，无需公网 URL）──

    private String submit(byte[] content, String fileName) throws Exception {
        SubmitDocParserJobAdvanceRequest request = new SubmitDocParserJobAdvanceRequest()
            .setFileName(fileName)
            .setFileNameExtension(extensionOf(fileName))
            .setLlmEnhancement(props.isLlmEnhancement())
            // 表格 HTML 输出：官方约束须与 LlmEnhancement 同时开启（llmEnhancement 关闭时自动失效）
            .setOutputHtmlTable(props.isOutputHtmlTable() && props.isLlmEnhancement())
            .setFileUrlObject(new ByteArrayInputStream(content));

        SubmitDocParserJobResponse response = client()
            .submitDocParserJobAdvance(request, new RuntimeOptions());

        var body = response.getBody();
        if (body == null || body.getData() == null || body.getData().getId() == null) {
            throw new ParsingException("DocMind 提交失败: code=%s message=%s"
                .formatted(body != null ? body.getCode() : "null",
                    body != null ? body.getMessage() : "null"));
        }
        String jobId = body.getData().getId();
        log.info("DocMind 解析任务已提交: jobId={}, file={}", jobId, fileName);
        return jobId;
    }

    // ── 2. 轮询状态 ──

    private void awaitCompletion(String jobId) throws Exception {
        for (int i = 0; i < props.getMaxPolls(); i++) {
            QueryDocParserStatusResponse response = client()
                .queryDocParserStatus(new QueryDocParserStatusRequest().setId(jobId));

            var data = response.getBody() != null ? response.getBody().getData() : null;
            String status = data != null ? data.getStatus() : null;

            if ("success".equalsIgnoreCase(status)) {
                log.info("DocMind 解析完成: jobId={}, pages={}, tables={}, images={}",
                    jobId,
                    data.getPageCountEstimate(), data.getTableCount(), data.getImageCount());
                return;
            }
            if ("failed".equalsIgnoreCase(status)) {
                throw new ParsingException("DocMind 解析任务失败: jobId=" + jobId);
            }
            Thread.sleep(props.getPollIntervalMs());
        }
        throw new ParsingException("DocMind 解析超时（%d 次轮询 × %dms）: jobId=%s"
            .formatted(props.getMaxPolls(), props.getPollIntervalMs(), jobId));
    }

    // ── 3. 分页拉取 layouts 并合并 ──

    @SuppressWarnings("unchecked")
    private ParsingResult fetchResult(String jobId) throws Exception {
        StringBuilder markdown = new StringBuilder();
        int tables = 0;
        int images = 0;
        int pageCount = 0;
        int layoutNum = 0;

        while (true) {
            GetDocParserResultResponse response = client().getDocParserResult(
                new GetDocParserResultRequest()
                    .setId(jobId)
                    .setLayoutNum(layoutNum)
                    .setLayoutStepSize(LAYOUT_STEP));

            Map<String, ?> data = response.getBody() != null ? response.getBody().getData() : null;
            if (data == null) {
                break;
            }
            List<Map<String, ?>> layouts = (List<Map<String, ?>>) data.get("layouts");
            if (layouts == null || layouts.isEmpty()) {
                break;
            }

            for (Map<String, ?> layout : layouts) {
                Object rawType = layout.get("type");
                String type = rawType == null ? "" : String.valueOf(rawType);
                if (type.contains("table")) {
                    tables++;
                    // 表格 HTML 存放在 llmResult 字段（需 OutputHtmlTable=true），可能包裹
                    // ``` 代码围栏——剥离后保留 <table> 供 2.3 HtmlProtectingSplitter 保护为
                    // TABLE Chunk；llmResult 缺失时降级 markdownContent/text（内容不丢、保护失效）
                    String tableBlock = stripCodeFence(asString(layout.get("llmResult")));
                    if (tableBlock.isBlank()) {
                        tableBlock = firstNonBlank(
                            layout.get("html"), layout.get("markdownContent"),
                            layout.get("markdown"), layout.get("text"));
                    }
                    markdown.append('\n').append(tableBlock).append('\n');
                } else if (type.contains("image") || type.contains("figure")) {
                    images++;
                    // 图片块不产出正文（IMAGE Chunk 的 vision 摘要见 9.2/2.4，默认关闭）
                } else {
                    // 正文优先 markdownContent（大模型版实际字段名）→ 防御式回退 markdown/text
                    markdown.append(firstNonBlank(
                        layout.get("markdownContent"), layout.get("markdown"),
                        layout.get("text"))).append('\n');
                }
                Object pageNum = layout.get("pageNum");
                if (pageNum instanceof Number n) {
                    pageCount = Math.max(pageCount, n.intValue() + 1);
                }
            }

            Object completed = data.get("completed");
            if (Boolean.TRUE.equals(completed) && layouts.size() < LAYOUT_STEP) {
                break;
            }
            layoutNum += layouts.size();
        }

        return new ParsingResult(markdown.toString().trim(), tables, images, pageCount);
    }

    // ── 辅助 ──

    private Client client() throws Exception {
        Client local = client;
        if (local == null) {
            synchronized (this) {
                if (client == null) {
                    if (isBlank(props.getAccessKeyId()) || isBlank(props.getAccessKeySecret())) {
                        throw new ParsingException(
                            "DocMind 凭证缺失：请配置环境变量 ALIYUN_ACCESS_KEY_ID / ALIYUN_ACCESS_KEY_SECRET");
                    }
                    Config config = new Config()
                        .setAccessKeyId(props.getAccessKeyId())
                        .setAccessKeySecret(props.getAccessKeySecret())
                        .setEndpoint(props.getEndpoint());
                    client = local = new Client(config);
                }
            }
        }
        return local;
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    /** null 安全转 String（null → null，供 stripCodeFence 链式处理） */
    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 剥离 Markdown 代码围栏包裹（llmResult 实测形如 {@code ```html\n...\n```}）。
     * 无围栏原样返回；围栏未闭合等异常形态不强行剥离，交由下游容错。
     */
    static String stripCodeFence(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        if (!trimmed.startsWith("```")) {
            return value;
        }
        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline < 0 || !trimmed.endsWith("```") || trimmed.length() <= 6) {
            return value;
        }
        return trimmed.substring(firstNewline + 1, trimmed.length() - 3).strip();
    }

    private static String extensionOf(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1) : "";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
