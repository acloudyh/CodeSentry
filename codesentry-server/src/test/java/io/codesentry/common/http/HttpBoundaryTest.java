package io.codesentry.common.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.common.api.ApiResponse;
import io.codesentry.common.api.PageBounds;
import io.codesentry.common.error.BusinessException;
import io.codesentry.common.error.GlobalExceptionHandler;
import io.codesentry.common.error.SafeErrorMessage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

/** 真实 HTTP 边界验证失败状态、字段列表、秘密不回显以及跨请求追踪隔离。 */
class HttpBoundaryTest {
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new Endpoints())
          .setControllerAdvice(new GlobalExceptionHandler())
          .addFilters(new RequestTraceFilter(new ObjectMapper()))
          .build();

  @Test
  void internalFailuresNeverExposeExceptionOrSubmittedSecrets() throws Exception {
    String trace = "0123456789abcdef0123456789abcdef";
    String body =
        mvc.perform(get("/test/fail").header(RequestTrace.HEADER, trace))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.traceId").value(trace))
            .andExpect(header().string(RequestTrace.HEADER, trace))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(body.contains("private-value"));
    assertFalse(body.contains("SELECT"));
    assertNull(MDC.get("traceId"));
  }

  @Test
  void validatesAllFieldsWithoutIncludingRejectedValues() throws Exception {
    mvc.perform(
            post("/test/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"first\":\"\",\"second\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors.length()").value(2));
  }

  @Test
  void businessErrorsAreSanitizedAndRateResponsesCarryRetryAfter() throws Exception {
    String body =
        mvc.perform(get("/test/business"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_CONFIG"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(body.contains("private-value"));
    mvc.perform(get("/test/rate"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "30"));
  }

  @Test
  void rejectsInjectedTraceAndKeepsSecurityHeadersOnSuccessfulResponses() throws Exception {
    String incoming = "untrusted-trace-with-secret";
    String trace =
        mvc.perform(get("/test/ok").header(RequestTrace.HEADER, incoming))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andReturn()
            .getResponse()
            .getHeader(RequestTrace.HEADER);
    assertNotEquals(incoming, trace);
    assertTrue(trace.matches("[a-f0-9]{32}"));
    assertNull(MDC.get("traceId"));
  }

  @Test
  void strictPaginationRejectsInvalidAndOversizedRequests() {
    PageBounds.require(1, 20);
    PageBounds.require(1, 100);
    assertThrows(BusinessException.class, () -> PageBounds.require(0, 20));
    assertThrows(BusinessException.class, () -> PageBounds.require(1, 101));
    assertThrows(BusinessException.class, () -> PageBounds.require(1, 0));
    assertEquals("安全错误", SafeErrorMessage.clean("jdbc:mysql://private-value", "安全错误"));
    assertFalse(SafeErrorMessage.clean("token=private-value", "安全错误").contains("private-value"));
  }

  @RestController
  static class Endpoints {
    @GetMapping("/test/fail")
    Object fail() {
      throw new IllegalStateException("SELECT password FROM users private-value");
    }

    @GetMapping("/test/business")
    Object business() {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "BAD_CONFIG", "token=private-value");
    }

    @GetMapping("/test/rate")
    Object rate() {
      throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "请稍后再试");
    }

    @GetMapping("/test/ok")
    Object ok() {
      return ApiResponse.success("ok");
    }

    @PostMapping("/test/validate")
    Object validate(@Valid @RequestBody Input input) {
      return input;
    }
  }

  static class Input {
    @NotBlank public String first;
    @NotBlank public String second;
  }
}
