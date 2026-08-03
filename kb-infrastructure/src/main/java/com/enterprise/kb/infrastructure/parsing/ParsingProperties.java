package com.enterprise.kb.infrastructure.parsing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 解析链路配置（设计文档 9.1，前缀 kb.parsing）
 *
 * <p><b>安全约束</b>：DocMind 的 RAM AccessKey 只经环境变量
 * （ALIYUN_ACCESS_KEY_ID / ALIYUN_ACCESS_KEY_SECRET）注入，配置文件中仅占位符，
 * 任何场景不得明文入库/入文档。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "kb.parsing")
public class ParsingProperties {

    /** DEEP 链路后端：docmind（主选）| qwen-ocr（备选） */
    private String provider = "docmind";

    /** PDF 默认走 DEEP；false 时仅低文本密度扫描件走 OCR、常规 PDF 走 NATIVE */
    private boolean deepByDefault = false;

    private final Docmind docmind = new Docmind();
    private final QwenOcr qwenOcr = new QwenOcr();

    @Getter
    @Setter
    public static class Docmind {
        private String endpoint = "docmind-api.cn-hangzhou.aliyuncs.com";
        private String accessKeyId;
        private String accessKeySecret;
        /** 大模型增强开关（文档解析大模型版） */
        private boolean llmEnhancement = true;
        /**
         * 表格 HTML 输出开关（OutputHtmlTable）：表格版面块的 HTML 解析内容经
         * llmResult 字段返回，供 2.3 保护式切分识别 {@code <table>}。
         * 官方约束：须与 llmEnhancement 同时开启，llmEnhancement=false 时自动失效。
         */
        private boolean outputHtmlTable = true;
        private long pollIntervalMs = 3000;
        private int maxPolls = 100;
    }

    @Getter
    @Setter
    public static class QwenOcr {
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String model = "qwen3.5-ocr";
        /** 复用百炼 Key（DASHSCOPE_API_KEY） */
        private String apiKey;
        private int dpi = 150;
        private int maxPages = 50;
    }
}
