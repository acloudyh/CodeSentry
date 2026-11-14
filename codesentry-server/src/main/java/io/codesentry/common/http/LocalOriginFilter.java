package io.codesentry.common.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.common.error.ApiProblem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 本机平台的 Origin/Host 边界，阻止第三方网页对本地 API 发起跨站请求；不是用户登录或鉴权。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LocalOriginFilter extends OncePerRequestFilter {
  private static final Set<String> LOCAL = Set.of("localhost", "127.0.0.1", "::1", "[::1]");
  private final ObjectMapper json;

  public LocalOriginFilter(ObjectMapper json) {
    this.json = json;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String origin = request.getHeader("Origin");
    if (!LOCAL.contains(request.getServerName())
        || origin != null && !sameOrigin(origin, request)) {
      ApiProblem problem =
          new ApiProblem(
              HttpStatus.FORBIDDEN, "CROSS_ORIGIN_FORBIDDEN", "只允许本机同源访问", "/api", List.of());
      response.setStatus(403);
      response.setContentType("application/problem+json");
      response.setCharacterEncoding("UTF-8");
      response.setHeader(RequestTrace.HEADER, problem.traceId);
      json.writeValue(response.getOutputStream(), problem);
      return;
    }
    chain.doFilter(request, response);
  }

  private boolean sameOrigin(String value, HttpServletRequest request) {
    try {
      URI origin = URI.create(value);
      int port =
          origin.getPort() < 0 ? ("https".equals(origin.getScheme()) ? 443 : 80) : origin.getPort();
      return request.getScheme().equals(origin.getScheme())
          && request.getServerName().equals(origin.getHost())
          && port == request.getServerPort()
          && origin.getRawUserInfo() == null
          && (origin.getRawPath() == null || origin.getRawPath().isEmpty())
          && origin.getRawQuery() == null
          && origin.getRawFragment() == null;
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }
}
