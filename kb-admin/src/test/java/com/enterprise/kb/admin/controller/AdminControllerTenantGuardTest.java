package com.enterprise.kb.admin.controller;

import com.enterprise.kb.admin.dto.AuditLogPage;
import com.enterprise.kb.admin.dto.ChunkUpdateRequest;
import com.enterprise.kb.admin.dto.DrillRequest;
import com.enterprise.kb.admin.dto.DrillResult;
import com.enterprise.kb.admin.dto.GuardrailRuleCreateRequest;
import com.enterprise.kb.admin.dto.GuardrailRulePage;
import com.enterprise.kb.admin.dto.GuardrailRuleUpdateRequest;
import com.enterprise.kb.admin.dto.RebuildRequest;
import com.enterprise.kb.admin.dto.ReingestRequest;
import com.enterprise.kb.admin.dto.ReloadResult;
import com.enterprise.kb.admin.dto.ResolvedRequest;
import com.enterprise.kb.admin.dto.RootCauseRequest;
import com.enterprise.kb.admin.service.AuditLogQueryService;
import com.enterprise.kb.admin.service.BadCaseService;
import com.enterprise.kb.admin.service.ChunkOpsService;
import com.enterprise.kb.admin.service.GuardrailAdminService;
import com.enterprise.kb.admin.service.GuardrailRuleOpsService;
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
import static org.mockito.ArgumentMatchers.isNull;
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
    private AuditLogQueryService auditLogQueryService;
    private BadCaseService badCaseService;
    private GuardrailAdminService guardrailAdminService;
    private GuardrailRuleOpsService guardrailRuleOpsService;
    private ChunkAdminController chunkController;
    private RebuildController rebuildController;
    private BadCaseAdminController badCaseController;
    private GuardrailAdminController guardrailController;

    @BeforeEach
    void setUp() {
        chunkOpsService = mock(ChunkOpsService.class);
        indexRebuildService = mock(IndexRebuildService.class);
        auditLogQueryService = mock(AuditLogQueryService.class);
        badCaseService = mock(BadCaseService.class);
        guardrailAdminService = mock(GuardrailAdminService.class);
        guardrailRuleOpsService = mock(GuardrailRuleOpsService.class);
        chunkController = new ChunkAdminController(chunkOpsService);
        rebuildController = new RebuildController(indexRebuildService);
        badCaseController = new BadCaseAdminController(auditLogQueryService, badCaseService);
        guardrailController = new GuardrailAdminController(guardrailAdminService, guardrailRuleOpsService);
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
        when(indexRebuildService.detail("t-1", "missing")).thenReturn(null);

        assertThatThrownBy(() -> rebuildController.task(jwtWithOwner("t-1"), "missing"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("REBUILD_TASK_NOT_FOUND");
        verify(indexRebuildService).detail("t-1", "missing");
    }

    /** v2.36：任务列表/详情按租户收敛，守卫透传 owner claim */
    @Test
    void rebuildListAndDetailPassOwnerClaimAsTenant() {
        when(indexRebuildService.detail("t-1", "task-1"))
            .thenReturn(new com.enterprise.kb.admin.dto.RebuildTaskView(
                "task-1", "RUNNING", 0, 0, 0, 0, null, null, List.of()));

        rebuildController.tasks(jwtWithOwner("t-1"));
        rebuildController.task(jwtWithOwner("t-1"), "task-1");

        verify(indexRebuildService).list("t-1");
        verify(indexRebuildService).detail("t-1", "task-1");
    }

    // ── BadCaseAdminController（簇④）──

    @Test
    void badCaseEndpointsRejectMissingTenant() {
        assertThatThrownBy(() -> badCaseController.search(null, null, null, null, null,
            null, null, null, null, null, null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        assertThatThrownBy(() -> badCaseController.annotate(jwtWithOwner(" "), 1L,
            new RootCauseRequest("HALLUCINATION")))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        assertThatThrownBy(() -> badCaseController.reingest(null,
            new ReingestRequest(1L, null, null, null, null, null)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        assertThatThrownBy(() -> badCaseController.resolve(null, "f-1", new ResolvedRequest(true)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        verify(auditLogQueryService, never()).search(anyString(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any());
        verify(badCaseService, never()).annotate(anyString(), any(), anyString());
    }

    @Test
    void badCaseSearchPassesOwnerClaimAsTenant() {
        when(auditLogQueryService.search(eq("t-1"), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any()))
            .thenReturn(new AuditLogPage(List.of(), 0, 0, 20));

        var response = badCaseController.search(jwtWithOwner("t-1"), null, null, null, null,
            "NEGATIVE", null, null, null, null, null);

        assertThat(response.data().total()).isZero();
        verify(auditLogQueryService).search(eq("t-1"), isNull(), isNull(), isNull(), isNull(),
            eq("NEGATIVE"), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void badCaseAnnotateAndReingestPassOwnerClaimAsTenant() {
        when(badCaseService.annotate("t-1", 3L, "RETRIEVAL_MISS")).thenReturn("RETRIEVAL_MISS");
        when(badCaseService.reingest(eq("t-1"), any()))
            .thenReturn(new com.enterprise.kb.admin.dto.ReingestResult(
                "bc-3", "f", "q", "FACTOID", null));

        var annotateResponse = badCaseController.annotate(jwtWithOwner("t-1"), 3L,
            new RootCauseRequest("RETRIEVAL_MISS"));
        var reingestResponse = badCaseController.reingest(jwtWithOwner("t-1"),
            new ReingestRequest(3L, null, null, null, null, null));

        assertThat(annotateResponse.data().get("rootCause")).isEqualTo("RETRIEVAL_MISS");
        assertThat(reingestResponse.data().goldenId()).isEqualTo("bc-3");
        verify(badCaseService).annotate("t-1", 3L, "RETRIEVAL_MISS");
        verify(badCaseService).reingest(eq("t-1"), any());
    }

    // ── GuardrailAdminController（安全簇⑥ F2）──

    @Test
    void guardrailEndpointsRejectMissingOrBlankTenant() {
        assertThatThrownBy(() -> guardrailController.listRules(null,
            null, null, null, null, null, null, null, null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        assertThatThrownBy(() -> guardrailController.listRules(jwtWithOwner(" "),
            null, null, null, null, null, null, null, null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        assertThatThrownBy(() -> guardrailController.drill(jwtWithOwner(null),
            new DrillRequest("任意文本")))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        verify(guardrailAdminService, never())
            .listRules(any(), any(), any(), any(), any(), any(), any(), any());
        verify(guardrailAdminService, never()).drill(anyString());
    }

    /** v2.53 写路径端点同款守卫：CRUD + 热更新触发 fail-closed */
    @Test
    void guardrailWriteEndpointsRejectMissingOrBlankTenant() {
        assertThatThrownBy(() -> guardrailController.createRule(null,
            new GuardrailRuleCreateRequest("injection", "UNCLASSIFIED", "eA==", null, null, null, null)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        assertThatThrownBy(() -> guardrailController.getRule(jwtWithOwner(" "), "r-1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        assertThatThrownBy(() -> guardrailController.updateRule(jwtWithOwner(null), "r-1",
            new GuardrailRuleUpdateRequest(null, null, null, null, null, false)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        assertThatThrownBy(() -> guardrailController.deleteRule(null, "r-1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        assertThatThrownBy(() -> guardrailController.reload(jwtWithOwner(" ")))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        verify(guardrailRuleOpsService, never()).create(any(), anyString());
        verify(guardrailRuleOpsService, never()).get(anyString());
        verify(guardrailRuleOpsService, never()).update(anyString(), any(), anyString());
        verify(guardrailRuleOpsService, never()).delete(anyString());
        verify(guardrailRuleOpsService, never()).reload();
    }

    @Test
    void guardrailReloadDelegatesToOpsServiceForValidTenant() {
        when(guardrailRuleOpsService.reload())
            .thenReturn(new ReloadResult("db", true, 10, 5));

        var response = guardrailController.reload(jwtWithOwner("t-1"));

        assertThat(response.data().reloaded()).isTrue();
        assertThat(response.data().source()).isEqualTo("db");
        verify(guardrailRuleOpsService).reload();
    }

    @Test
    void guardrailEndpointsDelegateToServiceForValidTenant() {
        when(guardrailAdminService.listRules(eq("injection"), isNull(), isNull(), isNull(),
            isNull(), isNull(), eq(0), eq(20)))
            .thenReturn(new GuardrailRulePage(List.of(), 0, 0, 20));
        when(guardrailAdminService.drill("演练文本"))
            .thenReturn(new DrillResult(List.of(), List.of()));

        var listResponse = guardrailController.listRules(jwtWithOwner("t-1"),
            "injection", null, null, null, null, null, 0, 20);
        var drillResponse = guardrailController.drill(jwtWithOwner("t-1"),
            new DrillRequest("演练文本"));

        assertThat(listResponse.data().items()).isEmpty();
        assertThat(drillResponse.data().injectionMatches()).isEmpty();
        verify(guardrailAdminService).listRules("injection", null, null, null, null, null, 0, 20);
        verify(guardrailAdminService).drill("演练文本");
    }
}
