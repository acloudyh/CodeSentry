package io.codesentry.ai.provider;

public interface AiReviewHttpClient {
    String postJson(String endpoint, String apiKey, String requestBody, int timeoutSeconds);
}
