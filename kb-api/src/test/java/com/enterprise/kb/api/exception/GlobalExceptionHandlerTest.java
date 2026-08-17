package com.enterprise.kb.api.exception;

import com.enterprise.kb.commons.dto.ApiResponse;
import com.enterprise.kb.commons.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandler 状态码映射测试（安全簇② B2 新增 413 通道）
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/documents/upload");

    @Test
    void fileTooLargeMappedTo413() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(
            new BusinessException("FILE_TOO_LARGE", "上传文件超过单文件 50MB 上限"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().code()).isEqualTo(413);
    }

    @Test
    void multipartResolverExceededMappedTo413() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleMaxUploadSizeExceeded(
            new MaxUploadSizeExceededException(50L * 1024 * 1024), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().code()).isEqualTo(413);
    }

    @Test
    void existingMappingsUnchanged() {
        // 配额类 429 / 冲突类 409 / 存储不可用 503 / 一般业务 400 回归
        assertThat(handler.handleBusinessException(
            new BusinessException("RATE_LIMITED", "x"), request).getStatusCode())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(handler.handleBusinessException(
            new BusinessException("DOC_NOT_READY", "x"), request).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.handleBusinessException(
            new BusinessException("APPROVAL_STORE_UNAVAILABLE", "x"), request).getStatusCode())
            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(handler.handleBusinessException(
            new BusinessException("DOC_NOT_FOUND", "x"), request).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
