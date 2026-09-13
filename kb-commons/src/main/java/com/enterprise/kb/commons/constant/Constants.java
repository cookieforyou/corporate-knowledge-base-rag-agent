package com.enterprise.kb.commons.constant;

/**
 * 全局常量定义
 *
 * <p>收敛原则（2026-09-12 定案）：仅收<strong>跨类/跨模块共享的协议性字面量</strong>——
 * 错误码（API 契约值）、检索路名与 metadata 键（写入↔读出契约）、对话协议值
 * （mode/SSE 事件/审计三态/消息角色）、身份协议值（JWT claims）、MCP 工具名
 * （注册↔审计契约）。有枚举归属的值域（DocumentStatus 等状态机）以枚举
 * 为单一事实源不入此类；配置键（rag.*）归 @ConfigurationProperties；日志/描述/
 * 提示语不收敛。分页常量与检索调优参数例外说明见下。
 */
public final class Constants {

    private Constants() {}

    /** 默认分页大小 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 最大分页大小 */
    public static final int MAX_PAGE_SIZE = 100;

    /** 摘要算法名（词表指纹 / 语义缓存键 / Golden 数据集与快照锚定共享） */
    public static final String DIGEST_SHA_256 = "SHA-256";

    // 注：检索调优参数（topK / RRF_K / 召回倍数 / 相似度阈值 / 单路超时）已于冲刺簇① A3
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

        // ── 编排（Phase5簇⑤）与 MCP ──
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
     * 等，含查询 DSL {@code field(...)} 与部分更新 doc 键）与 Cypher 参数名同字面
     * 分属 schema/存储契约，<strong>不经此类</strong>——ES/PG 侧字面与 metadata 键
     * 同值但属两契约（EsChunkDoc 注解域 vs Document metadata Map 通道），改动须
     * 双侧同步核验。
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

        // ── metadata 契约键（续，批5）：溯源展示键与租户/软删过滤键 ──
        // 写入侧 = DocumentEtlService.vectorMetadata（单一来源）+ ES/图路检索结果重组；
        // 读出侧 = 审计/溯源/MCP/编排工具/调试台/eval 探针 + 检索 FilterExpression。
        // tenant_id/is_deleted 承载租户隔离 fail-closed 与软删过滤语义，契约强度最高。
        // 注意：值域 chunk_type 的取值（TEXT/TABLE/IMAGE）归 kb-domain ChunkType 枚举，
        // 不在此列。
        public static final String META_FILE_NAME = "file_name";
        public static final String META_PAGE_NUM = "page_num";
        public static final String META_CHUNK_TYPE = "chunk_type";
        public static final String META_TENANT_ID = "tenant_id";
        public static final String META_IS_DELETED = "is_deleted";

        // ── 安全打标（安全簇④ D2：ETL 入库打标 ↔ RRF 融合降权 ↔ 间接注入扫描）──
        public static final String INJECTION_HIT = "injection_hit";
        public static final String INDIRECT_INJECTION_HIT = "indirect_injection_hit";

        // ── TRACE 溯源条目 source 终结名（rerank 后终局文档集；审计命中判定消费）──
        public static final String TRACE_SOURCE_FINAL = "final";
    }

    /**
     * 对话链路 mode（请求体 mode 字段值 / 审计 mode 列 / admin 查询过滤值；小写落库）
     *
     * <p>三值封闭值域，AgentController 分流（tool/agent 条件链）与各 ChatService
     * 的审计参数（AuditTraceAdvisor.MODE_KEY 的值）共享。
     */
    public static final class ChatMode {
        private ChatMode() {}

        public static final String MODE_RAG = "rag";
        public static final String MODE_TOOL = "tool";
        public static final String MODE_AGENT = "agent";
    }

    /**
     * SSE 命名事件（协议帧名；无名 TOKEN/ERROR/DONE 帧不经事件名，不在此列）
     *
     * <p>前端 TS 侧同值契约各自持有（跨语言无法共享常量，改动须双侧同步）。
     * PROGRESS_TYPE_STAGE 为 PROGRESS 事件的进度类型标签（检索/编排阶段播报）。
     */
    public static final class SseEvent {
        private SseEvent() {}

        public static final String TRACE = "TRACE";
        public static final String TOOL_CALL = "TOOL_CALL";
        public static final String REPLACE = "REPLACE";
        public static final String PROGRESS = "PROGRESS";
        public static final String PROGRESS_TYPE_STAGE = "stage";
    }

    /**
     * 审计三态（kb_audit_log.status / 指标 request.* 标签 / MCP 审计 / 导出过滤）
     *
     * <p>边界：RetrievalContext.ToolCall 的 STATUS_REJECTED（HITL 审批拒绝）与
     * 本三态同字面不同域，不引用此类。
     */
    public static final class AuditStatus {
        private AuditStatus() {}

        public static final String SUCCESS = "SUCCESS";
        public static final String REJECTED = "REJECTED";
        public static final String ERROR = "ERROR";
    }

    /**
     * 对话消息角色（kb_message.role 落库值域，大写）
     *
     * <p>写侧（ChatSessionService 归档）↔ 读侧（记忆回填 switch / 历史投影 /
     * 反馈与导出定位用户消息）共享的 DB 值域。与 Spring AI MessageType 解耦
     * （自家约定不随框架演进）；SFT/DPO 导出的小写 "user"/"assistant" 是
     * 开放训练格式契约，另有 "system" 语义缺省不落库，均不在此列。
     */
    public static final class MessageRole {
        private MessageRole() {}

        public static final String USER = "USER";
        public static final String ASSISTANT = "ASSISTANT";
    }

    /**
     * Casdoor JWT claims 键（sub→userId / name→username / owner→tenantId 映射）
     *
     * <p>身份协议字面量：JwtUtils 解析、SecurityConfig 超管判定、WS 握手、
     * kb-admin 各 Controller 与 MCP 身份守卫消费。claim 名是 Casdoor 侧契约，
     * 改动须与 IdP 配置同步。
     */
    public static final class JwtClaims {
        private JwtClaims() {}

        public static final String SUB = "sub";
        public static final String NAME = "name";
        public static final String OWNER = "owner";
    }

    /**
     * MCP Server 工具名（@McpTool 注册 ↔ 审计/指标统计）
     *
     * <p>kb-ai-agent McpKnowledgeTools 注册、McpAuditRecorder 审计与
     * AiBusinessMetrics 指标分流共享；外部 MCP 客户端按名发现，值即对外契约。
     */
    public static final class McpTool {
        private McpTool() {}

        public static final String SEARCH = "search";
        public static final String GET_DOCUMENT = "get_document";
        public static final String ASK = "ask";
    }

    /**
     * Advisor 链序值（各 advisor getOrder()/order() 返回值，链序表 11.2 的代码伴生单源）
     *
     * <p>链上拦截次序即协议（审计外层 → 配额护栏 → 路由门控 → 检索 → 工具循环），
     * 改动须同步 11.2 链序表。MEMORY 为 MessageChatMemoryAdvisor 构造序（rag/tool/
     * orchestrator 三链同值）；TOOL_CALLING 为工具循环 advisorOrder（tool/orchestrator
     * 共享 agentToolCallingAdvisor Bean）；RETRIEVAL_GATE(500) 与框架
     * RetrievalAugmentationAdvisor 默认序同值为包裹关系，非本类定义。
     */
    public static final class ChainOrder {
        private ChainOrder() {}

        public static final int AUDIT_TRACE = 10;
        public static final int TOKEN_BUDGET = 30;
        public static final int RATE_LIMIT = 100;
        public static final int OUTPUT_GUARDRAIL = 110;
        public static final int INPUT_SANITIZE = 300;
        public static final int SEMANTIC_INJECTION = 320;
        public static final int MEMORY = 400;
        public static final int TASK_BOUNDARY = 420;
        public static final int QUERY_ROUTING = 440;
        public static final int RETRIEVAL_TRACE = 450;
        public static final int CACHE_CHECK = 460;
        public static final int RETRIEVAL_GATE = 500;
        public static final int TOOL_CALLING = 1000;
    }

    /**
     * WS 进度协议键（ETL 进度 Redis Pub/Sub 帧 ↔ WS 订阅参数/帧解析）
     *
     * <p>EtlProgress record 字段经 Jackson 序列化为帧字段，WebSocketConfig 订阅
     * 解析与 Handler 查询参数按同键消费。
     */
    public static final class Ws {
        private Ws() {}

        /** EtlProgress 帧字段名 / WS 订阅查询参数名 */
        public static final String DOC_ID = "docId";
    }

    /**
     * Spring Bean 名（@Bean 定义 ↔ @Qualifier/@Async 注入与执行路由）
     *
     * <p>Bean 名是装配契约：改名编译期无校验（坑位㊺ 同型风险），定义点显式
     * name 引常量 + 消费点常量引用建立编译期单一来源。值 = 原方法名，零行为变化。
     */
    public static final class BeanNames {
        private BeanNames() {}

        // ── ChatModel 族 ──
        public static final String SMART_ROUTING_CHAT_MODEL = "smartRoutingChatModel";
        public static final String FALLBACK_CHAT_MODEL = "fallbackChatModel";
        public static final String PRIMARY_CHAT_MODEL = "primaryChatModel";
        public static final String GLM_CHAT_MODEL = "glmChatModel";
        public static final String DEEP_SEEK_CHAT_MODEL = "deepSeekChatModel";

        // ── ChatClient 族（多 Bean 纪律：注入点显式 @Qualifier）──
        public static final String CHAT_CLIENT = "chatClient";
        public static final String RAG_AGENT_CHAT_CLIENT = "ragAgentChatClient";
        public static final String TOOL_AGENT_CHAT_CLIENT = "toolAgentChatClient";
        public static final String ORCHESTRATOR_CHAT_CLIENT = "orchestratorChatClient";
        public static final String JUDGE_CHAT_CLIENT = "judgeChatClient";
        public static final String EVAL_GUARDRAIL_CHAT_CLIENT = "evalGuardrailChatClient";
        public static final String EVAL_GUARDRAIL_L2_CHAT_CLIENT = "evalGuardrailL2ChatClient";

        // ── Executor 族 ──
        public static final String ETL_EXECUTOR = "etlExecutor";
        public static final String RETRIEVAL_EXECUTOR = "retrievalExecutor";
        public static final String AUDIT_EXECUTOR = "auditExecutor";
        public static final String HYBRID_RETRIEVAL_EXECUTOR = "hybridRetrievalExecutor";
        public static final String ORCHESTRATOR_SUB_AGENT_EXECUTOR = "orchestratorSubAgentExecutor";
        public static final String GRAPH_CLEANUP_EXECUTOR = "graphCleanupExecutor";
        public static final String SESSION_ARCHIVE_EXECUTOR = "sessionArchiveExecutor";

        // ── 其他装配件 ──
        public static final String AGENT_CHAT_MEMORY = "agentChatMemory";
        public static final String REWRITE_QUERY_TRANSFORMER = "rewriteQueryTransformer";
    }
}
