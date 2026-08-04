package com.enterprise.kb.api.controller;

import com.enterprise.kb.ai.retriever.HybridDocumentRetriever;
import com.enterprise.kb.ai.retriever.RerankDocumentPostProcessor;
import com.enterprise.kb.api.security.JwtUtils;
import com.enterprise.kb.commons.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 检索调试台身份守卫测试（3.9+3.10）——调试台可见双路原始命中，
 * 无租户过滤时泄露面大于主链路，同样 fail-closed。
 */
class RetrievalDebugControllerTenantGuardTest {

    private final HybridDocumentRetriever hybridRetriever = mock(HybridDocumentRetriever.class);
    private final RerankDocumentPostProcessor rerankPostProcessor = mock(RerankDocumentPostProcessor.class);
    private final RewriteQueryTransformer rewriteQueryTransformer = mock(RewriteQueryTransformer.class);
    private final JwtUtils jwtUtils = mock(JwtUtils.class);

    private final RetrievalDebugController controller = new RetrievalDebugController(
        hybridRetriever, rerankPostProcessor, rewriteQueryTransformer, jwtUtils);

    @Test
    void searchWithoutTenantIdentity_rejectedBeforeRetrieval() {
        when(jwtUtils.getCurrentTenantId()).thenReturn(null);
        when(jwtUtils.getCurrentUserId()).thenReturn("user-1");

        assertThatThrownBy(() -> controller.search(Map.of("query", "问题")))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo("IDENTITY_INCOMPLETE");
        verifyNoInteractions(hybridRetriever);
        verifyNoInteractions(rewriteQueryTransformer);
    }
}
