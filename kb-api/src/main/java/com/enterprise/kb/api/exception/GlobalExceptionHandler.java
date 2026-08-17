package com.enterprise.kb.api.exception;

import com.enterprise.kb.commons.dto.ApiResponse;
import com.enterprise.kb.commons.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Set;

/**
 * 全局异常处理 — 将业务异常和系统异常统一转换为 {@link ApiResponse}
 *
 * <p>日志均携带 {@code method + URI}：同一 errorCode（如 DOC_NOT_FOUND）可由多个
 * 端点触发（详情/Chunk 列表/删除），缺请求上下文时无法定位来源（2026-08-03 实测教训）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 配额类拒绝错误码 — 限流/预算耗尽语义为「请求过量」，映射 HTTP 429
     * （区别于一般业务错误的 400）。流式路径不经此处：由 AgentController
     * onErrorResume 承接为 SSE ERROR 事件（与 PROMPT_INJECTION 同形态）。
     */
    private static final Set<String> QUOTA_ERROR_CODES = Set.of("RATE_LIMITED", "TOKEN_BUDGET_EXCEEDED");

    /**
     * 资源状态冲突类错误码 — 目标资源当前状态不允许该操作（簇⑥ C1：文档处于
     * 处理中仍发起重入库 / 处理期发起删除；簇③ 4.4：对未软删 chunk 发起恢复），
     * 语义为「与现状冲突」，映射 HTTP 409（可重试语义）。
     */
    private static final Set<String> CONFLICT_ERROR_CODES = Set.of("DOC_NOT_READY", "CHUNK_NOT_DELETED");

    /**
     * 依赖存储不可用类错误码 — Redis 故障致状态账本/任务表读写 fail-closed
     * （HITL 审批账本 / 重建任务表 v2.36），语义为「服务端依赖暂不可用」，
     * 映射 HTTP 503（区别于请求本身错误的 400，客户端可重试）。
     */
    private static final Set<String> STORE_UNAVAILABLE_ERROR_CODES =
        Set.of("APPROVAL_STORE_UNAVAILABLE", "REBUILD_STORE_UNAVAILABLE");

    /**
     * 载荷超限类错误码（安全簇② B2）— Service 层复核兜底拦截的大文件，
     * 语义为「请求载荷过大」，映射 HTTP 413（Servlet 层 multipart 超限
     * 另经 {@link #handleMaxUploadSizeExceeded} 同语义承接）。
     */
    private static final Set<String> PAYLOAD_TOO_LARGE_ERROR_CODES = Set.of("FILE_TOO_LARGE");

    /**
     * 业务异常 — 提取 errorCode 和 message；配额类（RATE_LIMITED /
     * TOKEN_BUDGET_EXCEEDED）返回 HTTP 429，状态冲突类返回 HTTP 409，
     * 存储不可用类返回 HTTP 503，载荷超限类返回 HTTP 413，其余 HTTP 400
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e, HttpServletRequest request) {
        HttpStatus status = QUOTA_ERROR_CODES.contains(e.getErrorCode())
            ? HttpStatus.TOO_MANY_REQUESTS
            : CONFLICT_ERROR_CODES.contains(e.getErrorCode())
                ? HttpStatus.CONFLICT
                : STORE_UNAVAILABLE_ERROR_CODES.contains(e.getErrorCode())
                    ? HttpStatus.SERVICE_UNAVAILABLE
                    : PAYLOAD_TOO_LARGE_ERROR_CODES.contains(e.getErrorCode())
                        ? HttpStatus.PAYLOAD_TOO_LARGE
                        : HttpStatus.BAD_REQUEST;
        log.warn("业务异常 [{}] {} {}: {}", e.getErrorCode(), request.getMethod(),
            request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(status).body(ApiResponse.error(status.value(), e.getMessage()));
    }

    /**
     * multipart 上传超限（安全簇② B2）— 超过 spring.servlet.multipart.max-file-size /
     * max-request-size 时由 multipart resolver 在进 Controller 前抛出，
     * 统一映射 HTTP 413（与 Service 层 FILE_TOO_LARGE 复核同语义）。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException e, HttpServletRequest request) {
        log.warn("上传超限 {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(ApiResponse.error(HttpStatus.PAYLOAD_TOO_LARGE.value(), "上传文件超过大小上限"));
    }

    /**
     * 参数校验异常 — Spring Validation 失败时触发
     */
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .reduce((a, b) -> a + "; " + b)
            .orElse("参数校验失败");
        log.warn("参数校验失败: {}", msg);
        return ApiResponse.error(400, msg);
    }

    /**
     * 兜底异常 — 未预期的运行时错误，返回 HTTP 500
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleException(Exception e, HttpServletRequest request) {
        log.error("系统异常 {} {}", request.getMethod(), request.getRequestURI(), e);
        return ApiResponse.error(500, "服务器内部错误，请稍后重试");
    }
}
