package com.enterprise.kb.api.exception;

import com.enterprise.kb.commons.dto.ApiResponse;
import com.enterprise.kb.commons.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
     * 业务异常 — 提取 errorCode 和 message，返回 HTTP 400
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBusinessException(BusinessException e, HttpServletRequest request) {
        log.warn("业务异常 [{}] {} {}: {}", e.getErrorCode(), request.getMethod(),
            request.getRequestURI(), e.getMessage());
        return ApiResponse.error(400, e.getMessage());
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
