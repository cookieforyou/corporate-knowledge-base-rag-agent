package com.enterprise.kb.api.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * ChatBodyLimitFilter 测试（安全簇② B2）：chat 请求体 Content-Length 护栏
 */
class ChatBodyLimitFilterTest {

    private final ChatBodyLimitFilter filter = new ChatBodyLimitFilter(DataSize.ofMegabytes(1));
    private final FilterChain chain = mock(FilterChain.class);

    @Test
    void oversizedChatBodyRejectedWith413() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chat/stream");
        request.setContent(new byte[(int) DataSize.ofMegabytes(2).toBytes()]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("\"code\":413");
        verifyNoInteractions(chain);   // 超限不进后续链路
    }

    @Test
    void normalChatBodyPassesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chat/stream");
        request.setContent("{\"message\":\"hi\"}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(request, response);
    }

    @Test
    void chunkedRequestWithoutContentLengthPasses() throws Exception {
        // 边界声明：未声明 Content-Length（chunked，运行时 getContentLengthLong=-1）
        // 不拦——Mock 缺省值同为非超限值，断言语义是「无声明头即放行」
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chat/stream");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void nonChatPathsSkipped() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/documents/upload");
        request.setContent(new byte[(int) DataSize.ofMegabytes(2).toBytes()]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        // 上传通道走 multipart 上限，不受本过滤器约束
        verify(chain).doFilter(request, response);
    }
}
