package com.enterprise.kb.api.controller;

import com.enterprise.kb.ai.agent.a2a.A2aAgentService;
import com.enterprise.kb.ai.agent.a2a.A2aIdentityGuard;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.constant.Constants;
import com.enterprise.kb.commons.exception.BusinessException;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A2A 协议端点（Phase5簇⑥ 批2，N3 最小形态；spike 判负后 D1-A 自研协议层）
 *
 * <p><b>协议形态（a2a-protocol.org v1.0 权威核验 2026-09-13）</b>：
 * JSON-RPC 2.0 over HTTP，方法名 PascalCase——本端点只实现 {@code SendMessage}
 * 同步应答（result = SendMessageResponse oneOf {"task": ...}，§9.4.1）；
 * 流式/pushNotification/任务生命周期轮询不声明不实现（能力位收窄，Card 同步
 * 声明），不支持方法返回 -32601 method-not-found。
 *
 * <p><b>版本纪律（§3.6.2 MUST）</b>：客户端每请求带 {@code A2A-Version} header，
 * <b>空值按 0.3 解释</b>——本端点仅支持 1.0，空/非 1.0 一律 -32009
 * VersionNotSupportedError（v0.3 完整兼容挂起：Part kind 判别/枚举小写形态的
 * 响应分版本代价超最小形态，触发 = 真实 v0.3 client 出现）。
 *
 * <p><b>数据形态（v1.0 破坏性变更 A.2.1）</b>：Part 无 kind 判别字段——
 * TextPart = {"text": "..."} 成员在场即判别；Role/TaskState 值 ProtoJSON
 * SCREAMING_SNAKE（ROLE_USER / TASK_STATE_COMPLETED，§5.5）；timestamp ISO 8601
 * UTC 毫秒（§5.6.1）；未知字段忽略（§5.7 SHOULD，Boot Jackson 默认满足）。
 *
 * <p><b>治理不外包</b>：身份（A2aIdentityGuard JWT fail-closed）/限流/审计/
 * 指标全在服务层（kb-ai-agent a2a/），本层只做协议解析与应答组装；对话链异常
 * （注入拒绝/限流/模型故障）统一 JSON-RPC error 固定话术零泄露。
 *
 * <p><b>缺省关纪律</b>：{@code rag.a2a.enabled} 缺省 false——关闭态本 Bean
 * 缺位，端点 404 语义自然缺位（SecurityConfig matcher 在场不暴露端点功能）。
 */
@Slf4j
@RestController
@ConditionalOnProperty(prefix = "rag.a2a", name = "enabled", havingValue = "true")
public class A2aController {

    // ── 协议字面量（v1.0 spec §5.4/§9.5；类内协议契约，未跨类注册消费不收敛 Constants）──
    private static final String JSONRPC_VERSION = "2.0";
    private static final String METHOD_SEND_MESSAGE = "SendMessage";
    private static final String SUPPORTED_PROTOCOL_VERSION = "1.0";
    private static final String TASK_STATE_COMPLETED = "TASK_STATE_COMPLETED";
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;
    private static final int VERSION_NOT_SUPPORTED = -32009;

    /** §5.6.1：ISO 8601 UTC 毫秒三位（yyyy-MM-dd'T'HH:mm:ss.SSS'Z'） */
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final A2aIdentityGuard identityGuard;
    private final A2aAgentService agentService;

    public A2aController(A2aIdentityGuard identityGuard, A2aAgentService agentService) {
        this.identityGuard = identityGuard;
        this.agentService = agentService;
    }

    /** A2A JSON-RPC 入口：版本与方法校验 → 身份 → 文本提取 → 治理编排 → Task(completed) 组装 */
    @PostMapping(value = "/a2a", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonRpcResponse handle(@RequestBody JsonRpcRequest request,
                                  @RequestHeader(value = "A2A-Version", required = false) String a2aVersion) {
        Object requestId = request == null ? null : request.id();
        // 版本校验前置（§3.6.2：空值 = 0.3 语义；仅支持 1.0）
        if (!SUPPORTED_PROTOCOL_VERSION.equals(a2aVersion)) {
            return error(requestId, VERSION_NOT_SUPPORTED,
                "仅支持 A2A 协议版本 1.0（请求头 A2A-Version 缺失按 0.3 解释）");
        }
        if (request == null || request.params() == null || request.params().message() == null) {
            return error(requestId, INVALID_PARAMS, "请求缺少 params.message");
        }
        if (!METHOD_SEND_MESSAGE.equals(request.method())) {
            return error(requestId, METHOD_NOT_FOUND, "Method not found: " + request.method());
        }
        String text = extractText(request.params().message());
        if (text.isBlank()) {
            return error(requestId, INVALID_PARAMS, "message.parts 缺少文本内容（仅支持 text part）");
        }
        // contextId：客户端携带则延续（多轮记忆域 a2a-{contextId}），缺失则服务端生成回填（§3.4.1 MAY）
        String contextId = resolveContextId(request.params().message());
        try {
            RetrievalContext ctx = identityGuard.requireIdentity();
            ctx.setTraceId(UUID.randomUUID().toString());
            String answer = agentService.handle(text, contextId, ctx);
            return success(requestId, answer, contextId);
        } catch (BusinessException e) {
            // 限流/注入拒绝/身份缺失统一固定话术（含 RATE_LIMITED 语义可辨，其余零细节泄露）
            log.info("A2A 请求拒绝（errorCode={}）: {}", e.getErrorCode(), contextId);
            String message = Constants.ErrorCodes.RATE_LIMITED.equals(e.getErrorCode())
                ? "请求过于频繁，请稍后再试" : "请求无法处理";
            return error(requestId, INTERNAL_ERROR, message);
        }
    }

    // ── 应答组装 ──

    private JsonRpcResponse success(Object requestId, String answer, String contextId) {
        Task task = new Task(UUID.randomUUID().toString(), contextId,
            new TaskStatus(TASK_STATE_COMPLETED, TIMESTAMP_FORMAT.format(Instant.now())),
            List.of(new Artifact(UUID.randomUUID().toString(), "answer", List.of(new Part(answer)))));
        return new JsonRpcResponse(JSONRPC_VERSION, requestId, new Result(task), null);
    }

    private static JsonRpcResponse error(Object requestId, int code, String message) {
        return new JsonRpcResponse(JSONRPC_VERSION, requestId, null, new ErrorBody(code, message));
    }

    /** parts 文本提取：text 成员在场即 TextPart（v1.0 成员判别）；非文本 part 跳过，多段换行拼接 */
    private static String extractText(Message message) {
        if (message.parts() == null) {
            return "";
        }
        return message.parts().stream()
            .map(Part::text)
            .filter(Objects::nonNull)
            .filter(t -> !t.isBlank())
            .collect(Collectors.joining("\n"));
    }

    private static String resolveContextId(Message message) {
        return message.contextId() != null && !message.contextId().isBlank()
            ? message.contextId() : UUID.randomUUID().toString();
    }

    // ── 协议 DTO（v1.0 spec §4 数据模型子集；record 直绑，未知字段忽略）──

    /** JSON-RPC 2.0 请求（§9.3）；id 透传回显（string/number 均可） */
    public record JsonRpcRequest(String jsonrpc, Object id, String method, Params params) {
    }

    /** SendMessageRequest（§3.2.1）——configuration/metadata 最小形态忽略 */
    public record Params(Message message) {
    }

    /** Message（§4.1.4）——taskId/referenceTaskIds/metadata/extensions 忽略（无任务续接面） */
    public record Message(String role, List<Part> parts, String messageId, String contextId) {
    }

    /** Part（§4.1.6 v1.0 成员判别）——只绑 text；非文本 part（raw/url/data）绑出 text=null 即跳过 */
    public record Part(String text) {
    }

    /** JSON-RPC 应答：result 与 error 互斥，null 侧省略（NON_NULL） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JsonRpcResponse(String jsonrpc, Object id, Result result, ErrorBody error) {
    }

    /** SendMessageResponse（§9.4.1 oneOf）——本端点恒走 task 分支 */
    public record Result(Task task) {
    }

    /** Task（§4.1.1）——history/metadata 最小形态不产出 */
    public record Task(String id, String contextId, TaskStatus status, List<Artifact> artifacts) {
    }

    /** TaskStatus（§4.1.2）——message 最小形态不产出 */
    public record TaskStatus(String state, String timestamp) {
    }

    /** Artifact（§4.1.7）——答案正文单一 text part */
    public record Artifact(String artifactId, String name, List<Part> parts) {
    }

    /** JSON-RPC error object（§9.5） */
    public record ErrorBody(int code, String message) {
    }
}
