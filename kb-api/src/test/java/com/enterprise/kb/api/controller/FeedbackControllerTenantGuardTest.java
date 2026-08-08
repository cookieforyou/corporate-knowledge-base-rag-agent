package com.enterprise.kb.api.controller;

import com.enterprise.kb.api.dto.FeedbackRequest;
import com.enterprise.kb.api.security.JwtUtils;
import com.enterprise.kb.api.service.FeedbackService;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.enums.FeedbackRating;
import com.enterprise.kb.domain.model.KbFeedback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 反馈 Controller 身份守卫测试（3.17，3.9 同款纪律）：tenantId 缺失即拒
 */
class FeedbackControllerTenantGuardTest {

    private final FeedbackService feedbackService = mock(FeedbackService.class);
    private final JwtUtils jwtUtils = mock(JwtUtils.class);
    private FeedbackController controller;

    @BeforeEach
    void setUp() {
        controller = new FeedbackController(feedbackService, jwtUtils);
    }

    @Test
    void submitWithoutTenantIdentityRejected() {
        when(jwtUtils.getCurrentTenantId()).thenReturn(null);

        assertThatThrownBy(() -> controller.submit(new FeedbackRequest(
            "m-1", null, "POSITIVE", null, null)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        verifyNoInteractions(feedbackService);
    }

    @Test
    void searchWithoutTenantIdentityRejected() {
        when(jwtUtils.getCurrentTenantId()).thenReturn("  ");

        assertThatThrownBy(() -> controller.search(null, null, null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        verifyNoInteractions(feedbackService);
    }

    @Test
    void submitReturnsFeedbackHandle() {
        when(jwtUtils.getCurrentTenantId()).thenReturn("tenant-a");
        when(jwtUtils.getCurrentUserId()).thenReturn("user-1");
        KbFeedback saved = new KbFeedback();
        saved.setId("fb-1");
        saved.setRating(FeedbackRating.NEGATIVE);
        when(feedbackService.submit(anyString(), anyString(), any())).thenReturn(saved);

        Map<String, Object> data = controller.submit(new FeedbackRequest(
            "m-1", null, "NEGATIVE", null, List.of())).data();

        assertThat(data).containsEntry("feedbackId", "fb-1").containsEntry("rating", "NEGATIVE");
    }
}
