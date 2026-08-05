package com.enterprise.kb.ai.agent.tool;

import com.enterprise.kb.commons.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具审批账本测试（3.4 复审要素①）—— TTL 挂账、绑定校验、一次性消费、
 * Redis 故障 fail-closed
 */
class ToolApprovalServiceTest {

    private RedissonClient redisson;
    private RMap<String, String> ledger;
    private ToolApprovalService service;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        ledger = mock(RMap.class);
        // doReturn 绕过泛型方法 getMap 的类型推断歧义
        doReturn(ledger).when(redisson).getMap(anyString());
        service = new ToolApprovalService(redisson, 10);
    }

    @Test
    void createPendingStoresLedgerWithTtl() {
        String approvalId = service.createPending("tenant-a", "user-1", "submitLeaveRequest", "摘要");

        assertThat(approvalId).isNotBlank();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisson).getMap(keyCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo(ToolApprovalService.KEY_PREFIX + approvalId);

        ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(ledger).putAll(fieldsCaptor.capture());
        assertThat(fieldsCaptor.getValue())
            .containsEntry("tenantId", "tenant-a")
            .containsEntry("userId", "user-1")
            .containsEntry("toolName", "submitLeaveRequest")
            .containsEntry("status", ToolApprovalService.STATUS_PENDING);
        verify(ledger).expire(Duration.ofMinutes(10));
    }

    @Test
    void approveSucceedsForBoundPendingApproval() {
        when(ledger.isEmpty()).thenReturn(false);
        when(ledger.get("tenantId")).thenReturn("tenant-a");
        when(ledger.get("userId")).thenReturn("user-1");
        when(ledger.get("status")).thenReturn(ToolApprovalService.STATUS_PENDING);

        assertThat(service.approve("apv-001", "tenant-a", "user-1")).isTrue();
        verify(ledger).put("status", ToolApprovalService.STATUS_APPROVED);
    }

    @Test
    void approveRefusedForCrossTenant() {
        when(ledger.isEmpty()).thenReturn(false);
        when(ledger.get("tenantId")).thenReturn("tenant-a");
        when(ledger.get("userId")).thenReturn("user-1");

        assertThat(service.approve("apv-001", "tenant-b", "user-1")).isFalse();
    }

    @Test
    void approveRefusedForUnknownApproval() {
        when(ledger.isEmpty()).thenReturn(true);

        assertThat(service.approve("apv-unknown", "tenant-a", "user-1")).isFalse();
    }

    @Test
    void consumeOnlyOnceThenKeyDeleted() {
        when(ledger.isEmpty()).thenReturn(false);
        when(ledger.get("tenantId")).thenReturn("tenant-a");
        when(ledger.get("userId")).thenReturn("user-1");
        when(ledger.get("status")).thenReturn(ToolApprovalService.STATUS_APPROVED);

        assertThat(service.consume("apv-001", "tenant-a", "user-1")).isTrue();
        verify(ledger).delete();
    }

    @Test
    void consumeRefusedWhenStillPending() {
        when(ledger.isEmpty()).thenReturn(false);
        when(ledger.get("tenantId")).thenReturn("tenant-a");
        when(ledger.get("userId")).thenReturn("user-1");
        when(ledger.get("status")).thenReturn(ToolApprovalService.STATUS_PENDING);

        assertThat(service.consume("apv-001", "tenant-a", "user-1")).isFalse();
    }

    @Test
    void consumeRefusedForCrossUser() {
        when(ledger.isEmpty()).thenReturn(false);
        when(ledger.get("tenantId")).thenReturn("tenant-a");
        when(ledger.get("userId")).thenReturn("user-1");

        assertThat(service.consume("apv-001", "tenant-a", "user-2")).isFalse();
    }

    @Test
    void redisFailureOnCreateFailsClosed() {
        when(redisson.getMap(anyString())).thenThrow(new RuntimeException("Redis down"));

        assertThatThrownBy(() -> service.createPending("tenant-a", "user-1", "tool", "摘要"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo("APPROVAL_STORE_UNAVAILABLE");
    }

    @Test
    void redisFailureOnConsumeFailsClosed() {
        when(ledger.isEmpty()).thenThrow(new RuntimeException("Redis down"));

        assertThatThrownBy(() -> service.consume("apv-001", "tenant-a", "user-1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo("APPROVAL_STORE_UNAVAILABLE");
    }
}
