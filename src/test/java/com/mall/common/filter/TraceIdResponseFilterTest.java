package com.mall.common.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;

/**
 * TraceId 响应过滤器单元测试：
 * 验证请求处理完成后从 MDC 读取 traceId 并写入响应头 X-Trace-Id，
 * 且无 traceId 时不写入。
 */
@ExtendWith(MockitoExtension.class)
class TraceIdResponseFilterTest {

    private final TraceIdResponseFilter filter = new TraceIdResponseFilter();

    @Mock
    private FilterChain filterChain;

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("MDC 含 traceId -> 响应头 X-Trace-Id 写入该值")
    void doFilter_withTraceId_setsResponseHeader() throws Exception {
        MDC.put("traceId", "abc123def456");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertEquals("abc123def456", response.getHeader("X-Trace-Id"));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("MDC 无 traceId -> 响应头不写入 X-Trace-Id")
    void doFilter_withoutTraceId_noHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertNull(response.getHeader("X-Trace-Id"));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("响应头已存在 X-Trace-Id -> 不覆盖")
    void doFilter_headerAlreadyPresent_notOverwritten() throws Exception {
        MDC.put("traceId", "new-trace-id");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("X-Trace-Id", "existing-trace-id");

        filter.doFilter(request, response, filterChain);

        assertEquals("existing-trace-id", response.getHeader("X-Trace-Id"));
    }
}
