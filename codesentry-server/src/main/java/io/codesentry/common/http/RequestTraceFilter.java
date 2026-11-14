package io.codesentry.common.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 在所有 HTTP 请求入口建立追踪与安全头；日志只记录路由，不记录查询串、请求体和凭据。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {
  private static final Logger LOG = LoggerFactory.getLogger(RequestTraceFilter.class);
  private final ObjectMapper json;

  public RequestTraceFilter(ObjectMapper json) {
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String previous = MDC.get("traceId");
    String traceId = RequestTrace.resolve(request.getHeader(RequestTrace.HEADER));
    MDC.put("traceId", traceId);
    response.setHeader(RequestTrace.HEADER, traceId);
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("X-Frame-Options", "DENY");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("Cache-Control", "no-store");
    // Controller 可以设置更严格的报告沙箱策略；这里仅提供 API 的默认拒绝策略。
    response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
    if (request.isSecure()) response.setHeader("Strict-Transport-Security", "max-age=31536000");
    long started = System.nanoTime();
    try {
      chain.doFilter(request, response);
    } finally {
      Map<String, Object> event = new LinkedHashMap<>();
      event.put("ts", Instant.now().toString());
      event.put("level", "INFO");
      event.put("service", "codesentry-server");
      event.put("traceId", traceId);
      event.put("method", request.getMethod());
      // 路由模板避免把查询参数、文件名或意外嵌入的密码写入访问日志。
      Object route =
          request.getAttribute(
              org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
      event.put("path", route == null ? "unmapped" : route.toString());
      event.put("status", response.getStatus());
      event.put("latencyMs", (System.nanoTime() - started) / 1_000_000);
      try {
        LOG.info(json.writeValueAsString(event));
      } finally {
        if (previous == null) MDC.remove("traceId");
        else MDC.put("traceId", previous);
      }
    }
  }
}
