package com.enterprise.kb.admin.controller;

import com.enterprise.kb.admin.dto.ChunkUpdateRequest;
import com.enterprise.kb.admin.dto.RebuildRequest;
import com.enterprise.kb.admin.service.ChunkOpsService;
import com.enterprise.kb.admin.service.IndexRebuildService;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.model.KbChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * kb-admin Controller 租户守卫测试（Phase 4 簇③）——@AuthenticationPrincipal Jwt
 * 直消费形态的 fail-closed 语义：JWT 缺失 / owner claim 空白 → IDENTITY_INCOMPLETE；
 * 合法 owner 透传服务层。
 */
class AdminControllerTenantGuardTest {

    private ChunkOpsService chunkOpsService;
    private IndexRebuildService indexRebuildService;
    private ChunkAdminController chunkController;
    private RebuildController rebuildController;

    @BeforeEach
    void setUp() {
        chunkOpsService = mock(ChunkOpsService.class);
        indexRebuildService = mock(IndexRebuildService.class);
        chunkController = new ChunkAdminController(chunkOpsService);
        rebuildController = new RebuildController(indexRebuildService);
    }

    private static Jwt jwtWithOwner(String owner) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
            .header("alg", "none")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600));
        if (owner != null) {
            builder.claim("owner", owner);
        }
        return builder.build();
    }

    // ── ChunkAdminController ──

    @Test
    void chunkEndpointsRejectMissingJwt() {
        assertThatThrownBy(() -> chunkController.edit(null, "c-1", new ChunkUpdateRequest("x")))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        assertThatThrownBy(() -> chunkController.softDelete(null, "c-1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        assertThatThrownBy(() -> chunkController.restore(null, "c-1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
    }

    @Test
    void chunkEndpointsRejectBlankOwnerClaim() {
        assertThatThrownBy(() -> chunkController.edit(jwtWithOwner(" "), "c-1", new ChunkUpdateRequest("x")))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        assertThatThrownBy(() -> chunkController.edit(jwtWithOwner(null), "c-1", new ChunkUpdateRequest("x")))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        verify(chunkOpsService, never()).edit(anyString(), anyString(), anyString());
    }

    @Test
    void editPassesOwnerClaimAsTenant() {
        KbChunk chunk = new KbChunk();
        chunk.setId("c-1");
        when(chunkOpsService.edit(eq("c-1"), eq("t-1"), eq("新内容")))
            .thenReturn(new ChunkOpsService.ChunkOpsResult(chunk, true));

        var response = chunkController.edit(jwtWithOwner("t-1"), "c-1", new ChunkUpdateRequest("新内容"));

        assertThat(response.data().id()).isEqualTo("c-1");
        verify(chunkOpsService).edit("c-1", "t-1", "新内容");
    }

    @Test
    void softDeleteAndRestorePassOwnerClaimAsTenant() {
        KbChunk chunk = new KbChunk();
        chunk.setId("c-1");
        when(chunkOpsService.softDelete(anyString(), anyString()))
            .thenReturn(new ChunkOpsService.ChunkOpsResult(chunk, true));
        when(chunkOpsService.restore(anyString(), anyString()))
            .thenReturn(new ChunkOpsService.ChunkOpsResult(chunk, true));

        chunkController.softDelete(jwtWithOwner("t-1"), "c-1");
        chunkController.restore(jwtWithOwner("t-1"), "c-1");

        verify(chunkOpsService).softDelete("c-1", "t-1");
        verify(chunkOpsService).restore("c-1", "t-1");
    }

    // ── RebuildController ──

    @Test
    void rebuildEndpointsRejectMissingTenant() {
        assertThatThrownBy(() -> rebuildController.start(null, new RebuildRequest(null)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        assertThatThrownBy(() -> rebuildController.tasks(null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        assertThatThrownBy(() -> rebuildController.task(null, "task-1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        verify(indexRebuildService, never()).start(anyString(), any());
    }

    @Test
    void rebuildStartPassesNullBodyAsFullMode() {
        when(indexRebuildService.start(eq("t-1"), eq(null)))
            .thenReturn(new com.enterprise.kb.admin.dto.RebuildTaskView(
                "task-1", "RUNNING", 0, 0, 0, 0, null, null, List.of()));

        var response = rebuildController.start(jwtWithOwner("t-1"), null);

        assertThat(response.data().taskId()).isEqualTo("task-1");
        verify(indexRebuildService).start("t-1", null);
    }

    @Test
    void rebuildTaskNotFoundRaisesBusinessError() {
        when(indexRebuildService.detail("missing")).thenReturn(null);

        assertThatThrownBy(() -> rebuildController.task(jwtWithOwner("t-1"), "missing"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("REBUILD_TASK_NOT_FOUND");
    }
}
