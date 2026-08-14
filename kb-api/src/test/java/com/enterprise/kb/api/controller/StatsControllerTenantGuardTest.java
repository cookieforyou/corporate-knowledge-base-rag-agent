package com.enterprise.kb.api.controller;

import com.enterprise.kb.api.dto.DocumentProcessingView;
import com.enterprise.kb.api.dto.StatsOverview;
import com.enterprise.kb.api.security.JwtUtils;
import com.enterprise.kb.api.service.StatsService;
import com.enterprise.kb.commons.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 统计 Controller 身份守卫测试（Phase 4 簇② 任务 4.6，3.9 同款纪律）：tenantId 缺失即拒
 */
class StatsControllerTenantGuardTest {

    private final StatsService statsService = mock(StatsService.class);
    private final JwtUtils jwtUtils = mock(JwtUtils.class);
    private StatsController controller;

    @BeforeEach
    void setUp() {
        controller = new StatsController(statsService, jwtUtils);
    }

    @Test
    void overviewWithoutTenantIdentityRejected() {
        when(jwtUtils.getCurrentTenantId()).thenReturn(null);

        assertThatThrownBy(() -> controller.overview())
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        verifyNoInteractions(statsService);
    }

    @Test
    void processingWithoutTenantIdentityRejected() {
        when(jwtUtils.getCurrentTenantId()).thenReturn("  ");

        assertThatThrownBy(() -> controller.processing())
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        verifyNoInteractions(statsService);
    }

    @Test
    void overviewDelegatesWithTenant() {
        when(jwtUtils.getCurrentTenantId()).thenReturn("tenant-a");
        when(statsService.overview("tenant-a")).thenReturn(new StatsOverview(
            1, Map.of(), 2, Map.of(), List.of()));

        assertThat(controller.overview().data().documentTotal()).isEqualTo(1);
    }

    @Test
    void processingDelegatesWithTenant() {
        when(jwtUtils.getCurrentTenantId()).thenReturn("tenant-a");
        when(statsService.processing("tenant-a")).thenReturn(
            new DocumentProcessingView(Map.of(), List.of()));

        assertThat(controller.processing().data().documents()).isEmpty();
    }
}
