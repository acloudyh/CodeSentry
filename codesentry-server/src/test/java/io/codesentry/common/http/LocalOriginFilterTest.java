package io.codesentry.common.http;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 本地无登录模式仍阻断浏览器跨站写入与 DNS 重绑定 Host，不将该边界当作用户权限。 */
class LocalOriginFilterTest {
  @Test
  void allowsSameOriginAndLocalNativeClients() throws Exception {
    assertEquals(200, call("localhost", "https://localhost:5173"));
    assertEquals(200, call("127.0.0.1", "https://127.0.0.1:5173"));
    assertEquals(200, call("localhost", null));
  }

  @Test
  void rejectsCrossSiteOpaqueMalformedAndRebindingOrigins() throws Exception {
    for (String origin :
        new String[] {
          "https://evil.example",
          "null",
          "https://localhost:5173/path",
          "https://localhost:5173?x=1",
          "https://localhost:5173#x",
          "https://user@localhost:5173",
          "%%%",
          "http://localhost:5173",
          "https://localhost"
        }) {
      assertEquals(403, call("localhost", origin), origin);
    }
    assertEquals(403, call("evil.example", "https://evil.example:5173"));
  }

  private int call(String host, String origin) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v2/report-archives");
    request.setScheme("https");
    request.setServerName(host);
    request.setServerPort(5173);
    if (origin != null) request.addHeader("Origin", origin);
    MockHttpServletResponse response = new MockHttpServletResponse();
    new LocalOriginFilter(new ObjectMapper()).doFilter(request, response, new MockFilterChain());
    if (response.getStatus() == 403)
      assertTrue(response.getContentAsString().contains("CROSS_ORIGIN_FORBIDDEN"));
    return response.getStatus();
  }
}
