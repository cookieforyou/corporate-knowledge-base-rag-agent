package com.enterprise.kb.infrastructure.parsing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * qwen3.5-ocr 视觉解析客户端（设计文档 9.1 低成本备选 + 扫描件 OCR 兜底，任务 2.2）
 *
 * <p>双重角色：
 * <ul>
 *   <li>{@code kb.parsing.provider=qwen-ocr} 时作为 DEEP 链路备选后端；</li>
 *   <li>任何 provider 下，SmartParsingRouter 的 OCR 路由（低文本密度扫描件）恒定使用本客户端。</li>
 * </ul>
 *
 * <p>管线：PDFBox 按页渲染 PNG → Base64 data URL → DashScope OpenAI 兼容多模态
 * chat/completions（复用 DASHSCOPE_API_KEY）→ 逐页文本拼接。非 PDF 输入直接回退
 * 原文（无需 OCR）。约 ¥0.01-0.02/页，受 maxPages 上限保护。
 */
@Slf4j
@Component
public class QwenVlOcrParsingClient implements ParsingServiceClient {

    private static final String OCR_PROMPT =
        "请完整提取这张文档图片中的全部文字内容，保持原有段落与层级结构，"
            + "表格以 Markdown 表格形式输出，不要添加解释性内容。";

    private final ParsingProperties.QwenOcr props;
    private final RestClient restClient;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public QwenVlOcrParsingClient(ParsingProperties properties) {
        this.props = properties.getQwenOcr();
        this.restClient = RestClient.builder().baseUrl(props.getBaseUrl()).build();
    }

    @Override
    public String providerName() {
        return "qwen-ocr";
    }

    @Override
    public ParsingResult parse(byte[] content, String fileName) {
        if (!isPdf(fileName)) {
            // 非 PDF（理论上 OCR 路由只收扫描件 PDF，防御性兜底）
            return new ParsingResult(new String(content), 0, 0, 1);
        }
        if (isBlank(props.getApiKey())) {
            throw new ParsingException("qwen-ocr 凭证缺失：请配置 DASHSCOPE_API_KEY 环境变量");
        }
        try (PDDocument pdf = Loader.loadPDF(content)) {
            PDFRenderer renderer = new PDFRenderer(pdf);
            int pages = Math.min(pdf.getNumberOfPages(), props.getMaxPages());
            StringBuilder text = new StringBuilder();
            List<ParsingResult.PageSegment> pageSegments = new ArrayList<>();
            int tables = 0;

            for (int i = 0; i < pages; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, props.getDpi(), ImageType.RGB);
                String pageText = ocrPage(toPngDataUrl(image), fileName, i + 1);
                if (pageText.contains("|")) {
                    tables += countMarkdownTables(pageText);
                }
                text.append(pageText).append("\n\n");
                if (!pageText.isBlank()) {
                    // 页级块下传 page_number → 落库 kb_chunk.page_num（与 DocMind 链路对齐）
                    pageSegments.add(new ParsingResult.PageSegment(i + 1, pageText.trim()));
                }
                log.debug("OCR 完成: file={}, page={}/{}, chars={}",
                    fileName, i + 1, pages, pageText.length());
            }
            return new ParsingResult(text.toString().trim(), pageSegments,
                tables, 0, pdf.getNumberOfPages());
        } catch (ParsingException e) {
            throw e;
        } catch (Exception e) {
            throw new ParsingException("qwen-ocr 解析失败: " + e.getMessage(), e);
        }
    }

    /** 单页 OCR：多模态 chat/completions（OpenAI 兼容形态） */
    private String ocrPage(String imageDataUrl, String fileName, int pageNo) {
        Map<String, Object> body = Map.of(
            "model", props.getModel(),
            "messages", List.of(Map.of(
                "role", "user",
                "content", List.of(
                    Map.of("type", "image_url", "image_url", Map.of("url", imageDataUrl)),
                    Map.of("type", "text", "text", OCR_PROMPT)))));

        String responseJson = restClient.post()
            .uri("/chat/completions")
            .headers(h -> h.setBearerAuth(props.getApiKey()))
            .body(body)
            .retrieve()
            .body(String.class);

        try {
            JsonNode root = MAPPER.readTree(responseJson);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (contentNode.isMissingNode() || contentNode.isNull()) {
                throw new ParsingException("qwen-ocr 响应无内容: file=%s page=%d".formatted(fileName, pageNo));
            }
            return contentNode.asText();
        } catch (ParsingException e) {
            throw e;
        } catch (Exception e) {
            throw new ParsingException("qwen-ocr 响应解析失败: " + e.getMessage(), e);
        }
    }

    private static String toPngDataUrl(BufferedImage image) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bos);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    /** 粗略统计 Markdown 表格数（表头分隔行 |---| 计数） */
    private static int countMarkdownTables(String text) {
        int count = 0;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("|") && trimmed.contains("---")) {
                count++;
            }
        }
        return count;
    }

    private static boolean isPdf(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".pdf");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
