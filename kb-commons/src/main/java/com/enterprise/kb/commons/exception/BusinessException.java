package com.enterprise.kb.commons.exception;

import com.enterprise.kb.commons.constant.Constants;

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
        super(Constants.ErrorCodes.RESOURCE_NOT_FOUND, message);
    }
}
