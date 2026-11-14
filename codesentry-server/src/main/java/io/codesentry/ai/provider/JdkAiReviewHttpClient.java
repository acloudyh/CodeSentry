package io.codesentry.ai.provider;

import io.codesentry.common.http.RequestTrace;
import io.codesentry.config.CodeSentryProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 模型 HTTP 边界：配置超时、传递追踪编号；付费 POST 不在底层透明重发。 */
@Component
public class JdkAiReviewHttpClient implements AiReviewHttpClient {
  private final HttpClient client;

  @org.springframework.beans.factory.annotation.Autowired
  public JdkAiReviewHttpClient(CodeSentryProperties properties) {
    this(
        HttpClient.newBuilder()
            .connectTimeout(
                Duration.ofSeconds(Math.max(1, properties.getAi().getConnectTimeoutSeconds())))
            .build());
  }

  JdkAiReviewHttpClient(HttpClient client) {
    this.client = client;
  }

  @Override
  public String postJson(String endpoint, String apiKey, String requestBody, int timeoutSeconds) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header(RequestTrace.HEADER, RequestTrace.current())
            .POST(HttpRequest.BodyPublishers.ofString(requestBody));
    if (StringUtils.hasText(apiKey)) builder.header("Authorization", "Bearer " + apiKey);
    try {
      io.codesentry.process.CancellableTasks.check();
      // 模型供应商未承诺该 POST 的幂等语义；429/5xx 交回有记录的任务调度，避免隐藏重复调用和费用。
      HttpResponse<String> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status < 200 || status >= 300) throw new IllegalStateException("AI 服务返回 HTTP " + status);
      if (response.body() != null && response.body().length() > 200_000)
        throw new IllegalStateException("AI 服务响应超过安全长度限制");
      return response.body();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("AI 服务调用被中断", exception);
    } catch (Exception exception) {
      if (exception instanceof IllegalStateException) throw (IllegalStateException) exception;
      throw new IllegalStateException(
          "AI 服务连接失败：" + exception.getClass().getSimpleName(), exception);
    }
  }
}
