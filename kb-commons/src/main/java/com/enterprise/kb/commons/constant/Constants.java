package com.enterprise.kb.commons.constant;

/**
 * 全局常量定义
 *
 * <p>收敛原则（2026-09-12 定案）：仅收<strong>跨类/跨模块共享的协议性字面量</strong>——
 * 错误码（API 契约值）、检索路名与 metadata 键（写入↔读出契约）、对话协议值
 * （mode/SSE 事件/审计三态）。有枚举归属的值域（DocumentStatus 等状态机）以枚举
 * 为单一事实源不入此类；配置键（rag.*）归 @ConfigurationProperties；日志/描述/
 * 提示语不收敛。分页常量与检索调优参数例外说明见下。
 */
public final class Constants {

    private Constants() {}

    /** 默认分页大小 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 最大分页大小 */
    public static final int MAX_PAGE_SIZE = 100;

    // 注：检索调优参数（topK / RRF_K / 召回倍数 / 相似度阈值 / 单路超时）已于簇① A3
    // 收编为 rag.retrieval.* 配置组（kb-ai-core RetrievalProperties），不再以常量硬编码。

    /**
     * 业务错误码（BusinessException errorCode）
     *
     * <p>值即 API 契约：HTTP 响应体 errorCode 字段、前端映射与 kb-eval 断言均按字面
     * 消费——改动值即破坏兼容，只可增不可改。GlobalExceptionHandler 按码族映射
     * HTTP 状态（配额类 429 / 冲突类 409 等），新码入此须同步核对其落档。
     */
    public static final class ErrorCodes {
        private ErrorCodes() {}

        // ── 身份与租户守卫 ──
        public static final String IDENTITY_INCOMPLETE = "IDENTITY_INCOMPLETE";
        public static final String MCP_SCOPE_DENIED = "MCP_SCOPE_DENIED";

        // ── 配额与限流 ──
        public static final String RATE_LIMITED = "RATE_LIMITED";
        public static final String TOKEN_BUDGET_EXCEEDED = "TOKEN_BUDGET_EXCEEDED";

        // ── 安全护栏 ──
        public static final String PROMPT_INJECTION = "PROMPT_INJECTION";

        // ── 通用资源 ──
        public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";

        // ── 文档与 ETL ──
        public static final String DOC_NOT_FOUND = "DOC_NOT_FOUND";
        public static final String KB_DOC_NOT_FOUND = "KB_DOC_NOT_FOUND";
        public static final String DOC_NOT_READY = "DOC_NOT_READY";
        public static final String DOC_FORBIDDEN = "DOC_FORBIDDEN";
        public static final String ETL_FAILED = "ETL_FAILED";
        public static final String CHUNK_NOT_FOUND = "CHUNK_NOT_FOUND";
        public static final String CHUNK_NOT_DELETED = "CHUNK_NOT_DELETED";

        // ── 会话与反馈 ──
        public static final String SESSION_NOT_FOUND = "SESSION_NOT_FOUND";
        public static final String MESSAGE_NOT_FOUND = "MESSAGE_NOT_FOUND";
        public static final String FEEDBACK_NOT_FOUND = "FEEDBACK_NOT_FOUND";
        public static final String INVALID_FEEDBACK = "INVALID_FEEDBACK";

        // ── 上传与文件 ──
        public static final String FILE_TYPE_UNSUPPORTED = "FILE_TYPE_UNSUPPORTED";
        public static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";
        public static final String FILE_EMPTY = "FILE_EMPTY";
        public static final String UPLOAD_FAILED = "UPLOAD_FAILED";

        // ── 编排（簇⑤）与 MCP ──
        public static final String ORCHESTRATOR_DISABLED = "ORCHESTRATOR_DISABLED";
        public static final String INVALID_MODE = "INVALID_MODE";
        public static final String MCP_QUERY_EMPTY = "MCP_QUERY_EMPTY";
        public static final String MCP_DOC_NOT_FOUND = "MCP_DOC_NOT_FOUND";

        // ── admin 运维闭环 ──
        public static final String AUDIT_LOG_NOT_FOUND = "AUDIT_LOG_NOT_FOUND";
        public static final String INVALID_ROOT_CAUSE = "INVALID_ROOT_CAUSE";
        public static final String INVALID_TIME_FORMAT = "INVALID_TIME_FORMAT";
        public static final String INVALID_FILTER = "INVALID_FILTER";
        public static final String INVALID_EXPORT_FORMAT = "INVALID_EXPORT_FORMAT";
        public static final String GUARDRAIL_RULE_NOT_FOUND = "GUARDRAIL_RULE_NOT_FOUND";
        public static final String GUARDRAIL_RULE_INVALID = "GUARDRAIL_RULE_INVALID";
        public static final String GUARDRAIL_RULE_DUPLICATE = "GUARDRAIL_RULE_DUPLICATE";
        public static final String REBUILD_TASK_NOT_FOUND = "REBUILD_TASK_NOT_FOUND";
        public static final String REBUILD_STORE_UNAVAILABLE = "REBUILD_STORE_UNAVAILABLE";
        public static final String GRAPH_DISABLED = "GRAPH_DISABLED";
        public static final String GRAPH_BACKFILL_RUNNING = "GRAPH_BACKFILL_RUNNING";

        // ── HITL 审批账本 ──
        public static final String APPROVAL_STORE_UNAVAILABLE = "APPROVAL_STORE_UNAVAILABLE";

        // ── kb-eval Golden 数据集 ──
        public static final String GOLDEN_DIR_UNAVAILABLE = "GOLDEN_DIR_UNAVAILABLE";
        public static final String GOLDEN_ENTRY_INVALID = "GOLDEN_ENTRY_INVALID";
        public static final String GOLDEN_FILE_CORRUPT = "GOLDEN_FILE_CORRUPT";
    }
}
