package com.enterprise.kb.commons.exception;

/**
 * 统一业务异常基类
 */
public class BusinessException extends RuntimeException {

    private final String errorCode;

    public BusinessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

/**
 * 资源未找到异常
 */
class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String message) {
        super("RESOURCE_NOT_FOUND", message);
    }
}

/**
 * Token 预算耗尽异常
 */
class TokenBudgetExceededException extends BusinessException {
    public TokenBudgetExceededException(String message) {
        super("TOKEN_BUDGET_EXCEEDED", message);
    }
}
