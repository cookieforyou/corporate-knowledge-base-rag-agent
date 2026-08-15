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
     * 业务异常 — 提取 errorCode 和 message；配额类（RATE_LIMITED /
     * TOKEN_BUDGET_EXCEEDED）返回 HTTP 429，状态冲突类返回 HTTP 409，其余 HTTP 400
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e, HttpServletRequest request) {
        HttpStatus status = QUOTA_ERROR_CODES.contains(e.getErrorCode())
            ? HttpStatus.TOO_MANY_REQUESTS
            : CONFLICT_ERROR_CODES.contains(e.getErrorCode())
                ? HttpStatus.CONFLICT
                : HttpStatus.BAD_REQUEST;
        log.warn("业务异常 [{}] {} {}: {}", e.getErrorCode(), request.getMethod(),
            request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(status).body(ApiResponse.error(status.value(), e.getMessage()));
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
