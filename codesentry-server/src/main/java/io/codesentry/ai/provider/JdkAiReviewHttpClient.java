package io.codesentry.ai.provider;

import io.codesentry.config.CodeSentryProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class JdkAiReviewHttpClient implements AiReviewHttpClient {
    private final HttpClient client;
    private final Sleeper sleeper;

    @org.springframework.beans.factory.annotation.Autowired
    public JdkAiReviewHttpClient(CodeSentryProperties properties) {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getAi().getConnectTimeoutSeconds())))
                .build(), Thread::sleep);
    }

    interface Sleeper { void sleep(long millis) throws InterruptedException; }
    JdkAiReviewHttpClient(HttpClient client, Sleeper sleeper) { this.client = client; this.sleeper = sleeper; }

    @Override
    public String postJson(String endpoint, String apiKey, String requestBody, int timeoutSeconds) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
        if (StringUtils.hasText(apiKey)) builder.header("Authorization", "Bearer " + apiKey);
        try {
            HttpRequest request = builder.build();
            for (int attempt = 0; attempt < 3; attempt++) {
                io.codesentry.process.CancellableTasks.check();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if ((status == 429 || (status >= 500 && status <= 504)) && attempt < 2) {
                    long delay = 1000L << attempt;
                    String retryAfter = response.headers().firstValue("Retry-After").orElse("");
                    try { delay = Math.max(delay, Long.parseLong(retryAfter) * 1000L); }
                    catch (NumberFormatException ignored) { /* Default exponential backoff. */ }
                    sleeper.sleep(Math.min(30000L, delay));
                    continue;
                }
                if (status < 200 || status >= 300) throw new IllegalStateException("AI 服务返回 HTTP " + status);
                if (response.body() != null && response.body().length() > 200_000)
                    throw new IllegalStateException("AI 服务响应超过安全长度限制");
                return response.body();
            }
            throw new IllegalStateException("AI 服务重试后仍不可用");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 服务调用被中断", exception);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException) throw (IllegalStateException) exception;
            throw new IllegalStateException("AI 服务连接失败：" + exception.getClass().getSimpleName(), exception);
        }
    }
}
