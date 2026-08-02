package com.enterprise.kb.infrastructure.parsing;

/**
 * 解析服务客户端 —— 可插拔后端抽象（设计文档 9.1）
 *
 * <p>实现即后端：{@link DocMindParsingClient}（主选，文档解析大模型版）/
 * {@link QwenVlOcrParsingClient}（低成本备选，兼作扫描件 OCR 兜底）。
 * SmartParsingRouter（2.1）按 {@code kb.parsing.provider} 选择 DEEP 后端，
 * OCR 路由恒定使用 qwen-ocr 实现。条件满足时可平滑新增后端（如自托管 Docling），
 * 架构不受影响（Docling 重估触发条件见 9.1 v2.1 注记）。
 */
public interface ParsingServiceClient {

    /**
     * 解析文档字节流为结构化 Markdown（表格保留 HTML 块）。
     *
     * @throws ParsingException 解析失败/服务不可用——由路由层决策降级（回落 NATIVE）或传播
     */
    ParsingResult parse(byte[] content, String fileName);

    /** 后端标识（对应 kb.parsing.provider 取值：docmind / qwen-ocr） */
    String providerName();

    /** 解析异常：服务不可用 / 任务失败 / 轮询超时 */
    class ParsingException extends RuntimeException {
        public ParsingException(String message) {
            super(message);
        }

        public ParsingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
