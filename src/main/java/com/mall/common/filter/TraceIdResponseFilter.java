package com.mall.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 响应头回传 traceId 过滤器。
 * <p>
 * 将当前请求的 traceId 写入响应头 {@code X-Trace-Id}，方便前端/客户端在报错时
 * 直接拿到 traceId 去 Zipkin 查询整条调用链，无需从日志里翻找。
 * <p>
 * 说明：
 * <ul>
 *     <li>traceId 由 Micrometer Tracing + Brave 在请求进入时生成并放入 MDC</li>
 *     <li>本过滤器仅做「读取 MDC 并回写响应头」，不修改 traceparent 传播协议</li>
 *     <li>order 设为最低优先级，确保在 tracing filter 之后执行，MDC 已就绪</li>
 * </ul>
 *
 * @author mall
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TraceIdResponseFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String MDC_TRACE_ID_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 请求处理完成后从 MDC 取 traceId 写入响应头（此时 MDC 尚未清理）
            String traceId = MDC.get(MDC_TRACE_ID_KEY);
            if (traceId != null && !response.getHeaderNames().contains(TRACE_ID_HEADER)) {
                response.setHeader(TRACE_ID_HEADER, traceId);
            }
        }
    }
}
