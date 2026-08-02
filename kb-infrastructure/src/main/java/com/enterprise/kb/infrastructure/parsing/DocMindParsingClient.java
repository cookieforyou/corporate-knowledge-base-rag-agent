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
 * <p>结果载荷为无类型 Map（SDK 未建模 Data 内层），按防御式多键回退提取：
 * 正文优先 markdown→text，表格优先 html 块（保留 {@code <table>} 供 2.3 保护切分）。
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
                    // 表格保留 HTML 块——2.3 HtmlProtectingSplitter 识别 <table> 保护为 TABLE Chunk
                    markdown.append('\n').append(firstNonBlank(
                        layout.get("html"), layout.get("markdown"), layout.get("text"))).append('\n');
                } else if (type.contains("image") || type.contains("figure")) {
                    images++;
                    // 图片块不产出正文（IMAGE Chunk 的 vision 摘要见 9.2/2.4，默认关闭）
                } else {
                    markdown.append(firstNonBlank(
                        layout.get("markdown"), layout.get("text"))).append('\n');
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

    private static String extensionOf(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1) : "";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
