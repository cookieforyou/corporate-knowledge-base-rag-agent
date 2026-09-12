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

    /**
     * 检索域契约字面量（路名 + Document metadata 键 + TRACE 条目名）
     *
     * <p>写入侧（retriever / RrfFusion / Rerank / ETL）与读出侧（Controller 溯源 /
     * 审计 / MCP / 编排工具 / 调试台 / eval 探针）共享的键面。排名键族为
     * {@code ROUTE_X + RANK_KEY_SUFFIX} 拼接形态（编译期常量），不另立完整键常量
     * 防双源漂移。
     *
     * <p>边界：PG 列名（@Column "doc_id"）、ES 文档字段名（@JsonProperty "chunk_id"
     * 等）与 Cypher 参数名同字面分属 schema/存储契约，<strong>不经此类</strong>。
     */
    public static final class Retrieval {
        private Retrieval() {}

        // ── 检索路名（多路提交 / trace source / 调试台分流 / 降级矩阵 / 溯源判定）──
        public static final String ROUTE_VECTOR = "vector";
        public static final String ROUTE_BM25 = "bm25";
        public static final String ROUTE_GRAPH = "graph";

        // ── 排名键族后缀：{route}_rank（RrfFusion 动态拼接写入，SCORE_KEYS 读出）──
        public static final String RANK_KEY_SUFFIX = "_rank";

        // ── metadata 契约键 ──
        public static final String META_RETRIEVAL_SOURCE = "retrieval_source";
        public static final String META_FUSION_SCORE = "fusion_score";
        public static final String META_RERANK_SCORE = "rerank_score";
        public static final String META_RERANK_RANK = "rerank_rank";
        public static final String META_BM25_SCORE = "bm25_score";
        public static final String META_GRAPH_SCORE = "graph_score";
        public static final String META_GRAPH_ENTITY_HITS = "graph_entity_hits";
        public static final String META_DOC_ID = "doc_id";
        public static final String META_CHUNK_ID = "chunk_id";
        public static final String META_HEADING_PATH = "heading_path";

        // ── 安全打标（安全簇④ D2：ETL 入库打标 ↔ RRF 融合降权 ↔ 间接注入扫描）──
        public static final String INJECTION_HIT = "injection_hit";
        public static final String INDIRECT_INJECTION_HIT = "indirect_injection_hit";

        // ── TRACE 溯源条目 source 终结名（rerank 后终局文档集；审计命中判定消费）──
        public static final String TRACE_SOURCE_FINAL = "final";
    }
}
