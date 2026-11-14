package io.codesentry.ai.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.codesentry.common.http.RequestTrace;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 验证付费 POST 只发送一次、追踪传播、取消以及响应长度边界。 */
class JdkAiReviewHttpClientTest {
  @SuppressWarnings("unchecked")
  private HttpResponse<String> response(int status, String body) {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn(body);
    return response;
  }

  @Test
  void nonIdempotentPaidPostIsNotTransparentlyRetried() throws Exception {
    for (int status : new int[] {401, 429, 500, 503}) {
      HttpClient http = mock(HttpClient.class);
      HttpResponse<String> reply = response(status, "private-response");
      when(http.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
          .thenReturn(reply);
      IllegalStateException error =
          assertThrows(
              IllegalStateException.class,
              () ->
                  new JdkAiReviewHttpClient(http).postJson("http://mock/v1", "test-key", "{}", 10));
      assertTrue(error.getMessage().contains("HTTP " + status));
      assertFalse(error.getMessage().contains("private-response"));
      verify(http, times(1)).send(any(), any());
    }
  }

  @Test
  void successfulRequestIncludesTraceAndExplicitTimeout() throws Exception {
    HttpClient http = mock(HttpClient.class);
    HttpResponse<String> reply = response(200, "{}");
    when(http.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenReturn(reply);
    assertEquals("{}", new JdkAiReviewHttpClient(http).postJson("http://mock/v1", "", "{}", 10));
    ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
    verify(http).send(request.capture(), any());
    assertTrue(
        request
            .getValue()
            .headers()
            .firstValue(RequestTrace.HEADER)
            .orElseThrow()
            .matches("[a-f0-9]{32}"));
    assertEquals(10, request.getValue().timeout().orElseThrow().getSeconds());
    assertTrue(request.getValue().headers().firstValue("Authorization").isEmpty());
  }

  @Test
  void interruptedTransportPreservesInterruptAndDoesNotRetry() throws Exception {
    HttpClient http = mock(HttpClient.class);
    when(http.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenThrow(new InterruptedException());
    try {
      assertThrows(
          IllegalStateException.class,
          () -> new JdkAiReviewHttpClient(http).postJson("http://mock/v1", "", "{}", 10));
      assertTrue(Thread.currentThread().isInterrupted());
      verify(http).send(any(), any());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void longResponseAndConnectionErrorDoNotLeakResponseOrEndpoint() throws Exception {
    HttpClient http = mock(HttpClient.class);
    HttpResponse<String> reply = response(200, "x".repeat(200_001));
    when(http.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenReturn(reply);
    assertThrows(
        IllegalStateException.class,
        () -> new JdkAiReviewHttpClient(http).postJson("http://mock/v1", "", "{}", 10));
    when(http.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenThrow(new java.io.IOException("private-value"));
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> new JdkAiReviewHttpClient(http).postJson("http://mock/v1", "", "{}", 10));
    assertFalse(error.getMessage().contains("private-value"));
  }
}
