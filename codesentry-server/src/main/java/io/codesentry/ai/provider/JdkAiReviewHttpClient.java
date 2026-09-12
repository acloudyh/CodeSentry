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

    public JdkAiReviewHttpClient(CodeSentryProperties properties) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getAi().getConnectTimeoutSeconds())))
                .build();
    }

    @Override
    public String postJson(String endpoint, String apiKey, String requestBody, int timeoutSeconds) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
        if (StringUtils.hasText(apiKey)) builder.header("Authorization", "Bearer " + apiKey);
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("AI 服务返回 HTTP " + response.statusCode());
            }
            if (response.body() != null && response.body().length() > 200_000) {
                throw new IllegalStateException("AI 服务响应超过安全长度限制");
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 服务调用被中断", exception);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException) throw (IllegalStateException) exception;
            throw new IllegalStateException("AI 服务连接失败：" + exception.getClass().getSimpleName(), exception);
        }
    }
}
