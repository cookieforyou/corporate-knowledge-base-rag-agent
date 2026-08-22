package com.enterprise.kb.etl.reader;

import com.enterprise.kb.domain.enums.ParseRoute;
import com.enterprise.kb.infrastructure.parsing.ParsingProperties;
import com.enterprise.kb.infrastructure.parsing.ParsingResult;
import com.enterprise.kb.infrastructure.parsing.ParsingServiceClient;
import com.enterprise.kb.infrastructure.parsing.ParsingServiceClient.ParsingException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能解析路由器（设计文档 9.1，任务 2.1）——文本密度探测 + 三路由动态决策
 *
 * <pre>
 * 决策树（未被上传参数强制指定时）：
 *   非 PDF（md/txt/html/docx/pptx/xlsx）→ NATIVE（Tika 最优解；4.14 扩容的 PPTX/XLSX 经 tika-parser-microsoft-module 天然兼容）
 *   PDF 且 deep-by-default=true    → DEEP（显式配置优先；DocMind 电子版/扫描件通吃）
 *   PDF 且文本密度 &lt; 50 字符/页   → OCR（疑似扫描件，qwen3.5-ocr 视觉识别）
 *   其余                           → NATIVE
 * </pre>
 *
 * <p>v2.1 实施注记：设计稿的「表格区域占比」探测需要版面分析引擎，解析前不可得
 * （Tika 的 PDF 输出无表格结构）——故表格密集文档的 DEEP 路由经配置开关
 * （{@code kb.parsing.deep-by-default}）与上传参数（parseRoute=DEEP）显式触发，
 * 密度探测仅承担扫描件 OCR 识别。路由结构保持设计稿形态，未来版面探针
 * （如 DocMind 轻量首趟）可插入 decide() 而不改上下游。
 *
 * <p>容错：自动路由下 DEEP/OCR 服务失败回落 NATIVE（警告级）；显式指定路由
 * 失败如实上抛（用户意图优先，ETL 标记 FAILED 附错误信息）。
 */
@Slf4j
@Component
public class SmartParsingRouter {

    /** 文本密度阈值（字符/页）：低于此值疑似扫描件 → OCR 路由 */
    private static final double TEXT_DENSITY_THRESHOLD = 50.0;
    /** 密度探测页数上限（避免大文档全量提取） */
    private static final int PROBE_PAGES = 3;

    private final ParsingProperties properties;
    private final List<ParsingServiceClient> parsingClients;

    public SmartParsingRouter(ParsingProperties properties, List<ParsingServiceClient> parsingClients) {
        this.properties = properties;
        this.parsingClients = parsingClients;
    }

    /** 路由结果：解析产物 + 实际路由（落库 kb_document.parse_route） */
    public record ParsingOutcome(List<Document> documents, ParseRoute route) {}

    /**
     * 解析文档字节流。
     *
     * @param forced 上传参数强制指定的路由（null = 自动决策）
     */
    public ParsingOutcome read(byte[] content, String fileName, ParseRoute forced) {
        var route = forced != null ? forced : decide(content, fileName);
        log.info("解析路由决策: file={}, route={}{}", fileName, route,
            forced != null ? "（上传参数强制指定）" : "");
        return switch (route) {
            case NATIVE -> new ParsingOutcome(parseNative(content), route);
            case DEEP -> parseViaDeep(content, fileName, forced == null);
            case OCR -> parseViaOcr(content, fileName, forced == null);
        };
    }

    /** 自动决策：类型 + 密度探测 + 配置开关 */
    private ParseRoute decide(byte[] content, String fileName) {
        if (!isPdf(fileName)) {
            return ParseRoute.NATIVE;
        }
        if (properties.isDeepByDefault()) {
            return ParseRoute.DEEP;   // 显式配置优先于启发式
        }
        double density = probeTextDensity(content);
        if (density < TEXT_DENSITY_THRESHOLD) {
            log.info("文本密度 {} 字符/页 < 阈值 {}，判定为扫描件",
                String.format("%.1f", density), TEXT_DENSITY_THRESHOLD);
            return ParseRoute.OCR;
        }
        return ParseRoute.NATIVE;
    }

    private List<Document> parseNative(byte[] content) {
        return new TikaDocumentReader(new ByteArrayResource(content)).get();
    }

    private ParsingOutcome parseViaDeep(byte[] content, String fileName, boolean fallbackToNative) {
        ParsingServiceClient client = clientOf(properties.getProvider());
        try {
            ParsingResult result = client.parse(content, fileName);
            return new ParsingOutcome(toDocuments(result), ParseRoute.DEEP);
        } catch (ParsingException e) {
            if (!fallbackToNative) {
                throw e;   // 显式指定路由：如实上抛
            }
            log.warn("DEEP 解析失败，回落 NATIVE: file={}, {}", fileName, e.getMessage());
            return new ParsingOutcome(parseNative(content), ParseRoute.NATIVE);
        }
    }

    private ParsingOutcome parseViaOcr(byte[] content, String fileName, boolean fallbackToNative) {
        ParsingServiceClient client = clientOf("qwen-ocr");
        try {
            ParsingResult result = client.parse(content, fileName);
            return new ParsingOutcome(toDocuments(result), ParseRoute.OCR);
        } catch (ParsingException e) {
            if (!fallbackToNative) {
                throw e;
            }
            log.warn("OCR 解析失败，回落 NATIVE: file={}, {}", fileName, e.getMessage());
            return new ParsingOutcome(parseNative(content), ParseRoute.NATIVE);
        }
    }

    private ParsingServiceClient clientOf(String providerName) {
        return parsingClients.stream()
            .filter(c -> c.providerName().equals(providerName))
            .findFirst()
            .orElseThrow(() -> new ParsingException("解析后端不可用: " + providerName));
    }

    /**
     * 深度解析结果 → Document 列表（统计元数据随文档流转，落库 kb_document）
     *
     * <p>有页级信息时按页输出（每页一个 Document，page_number 元数据经
     * HtmlProtectingSplitter 原样下传 → 落库 kb_chunk.page_num，kb_chunk 溯源定位到页）；
     * 无页级信息（pages 为空）整篇输出。
     */
    private static List<Document> toDocuments(ParsingResult result) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("table_count", result.tableCount());
        stats.put("image_count", result.imageCount());
        stats.put("page_count", result.pageCount());

        if (result.pages() == null || result.pages().isEmpty()) {
            return List.of(Document.builder().text(result.markdown()).metadata(stats).build());
        }
        List<Document> documents = new ArrayList<>();
        for (ParsingResult.PageSegment page : result.pages()) {
            Map<String, Object> meta = new HashMap<>(stats);
            meta.put("page_number", page.pageNum());   // ETL persistChunks 读取此键
            documents.add(Document.builder().text(page.content()).metadata(meta).build());
        }
        return documents;
    }

    /** PDFBox 文本密度探测：前 PROBE_PAGES 页的平均字符数/页 */
    private double probeTextDensity(byte[] content) {
        try (PDDocument pdf = Loader.loadPDF(content)) {
            int pages = Math.min(pdf.getNumberOfPages(), PROBE_PAGES);
            if (pages == 0) {
                return 0;
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(pages);
            return stripper.getText(pdf).length() / (double) pages;
        } catch (Exception e) {
            log.debug("文本密度探测失败（按常规文档处理）: {}", e.getMessage());
            return Double.MAX_VALUE;   // 探测失败 → 不触发 OCR，走常规路径
        }
    }

    private static boolean isPdf(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".pdf");
    }
}
