package io.codesentry.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.ai.dto.AiReviewDecision;
import io.codesentry.ai.dto.AiReviewEvidence;
import io.codesentry.config.CodeSentryProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiCompatibleReviewProviderTest {
    @Test
    void retriesTruncationWithLargerBudgetAndDisablesDeepseekThinkingByDefault() throws Exception {
        CodeSentryProperties properties = configured();
        java.util.List<Integer> budgets = new java.util.ArrayList<>();
        ObjectMapper json = new ObjectMapper();
        OpenAiCompatibleReviewProvider actual = new OpenAiCompatibleReviewProvider(properties, json,
                (endpoint, key, body, timeout) -> {
                    try {
                        com.fasterxml.jackson.databind.JsonNode request = json.readTree(body);
                        budgets.add(request.path("max_tokens").asInt());
                        assertEquals("disabled", request.path("thinking").path("type").asText());
                        if (budgets.size() == 1) return "{\"choices\":[{\"finish_reason\":\"length\"}]}";
                        return json.writeValueAsString(java.util.Map.of("choices", java.util.List.of(
                                java.util.Map.of("finish_reason", "stop", "message", java.util.Map.of("content",
                                        "{\"verdict\":\"CONDITIONAL\",\"confidence\":0.9,\"reason\":\"需要核查\"}")))));
                    } catch (java.io.IOException error) { throw new IllegalStateException(error); }
                });
        assertEquals("CONDITIONAL", actual.review(new AiReviewEvidence(), "VERIFY", null).getVerdict());
        assertEquals(java.util.List.of(4096, 8192), budgets);
    }

    @Test void repeatedTruncationIsNeverAcceptedAsFalsePositive() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        OpenAiCompatibleReviewProvider actual = new OpenAiCompatibleReviewProvider(configured(), new ObjectMapper(),
                (endpoint, key, body, timeout) -> {
                    calls.incrementAndGet();
                    return "{\"choices\":[{\"finish_reason\":\"length\"}]}";
                });
        assertThrows(IllegalStateException.class, () -> actual.review(new AiReviewEvidence(), "PRIMARY", null));
        assertEquals(2, calls.get());
    }

    private CodeSentryProperties configured() {
        CodeSentryProperties properties = new CodeSentryProperties();
        properties.getAi().setEnabled(true);
        properties.getAi().setBaseUrl("http://mock/v1");
        properties.getAi().setModel("deepseek-v4-pro");
        return properties;
    }
    private final OpenAiCompatibleReviewProvider provider = new OpenAiCompatibleReviewProvider(
            new CodeSentryProperties(), new ObjectMapper(), (endpoint, key, body, timeout) -> "{}");

    @Test
    void parsesStrictJsonAndMarkdownFence() {
        AiReviewDecision decision = provider.parseDecision("```json\n{\"verdict\":\"CONDITIONAL\","
                + "\"confidence\":0.81,\"reason\":\"需要外部可控输入\",\"evidenceLines\":[12],"
                + "\"missingEvidence\":[\"调用方\"],\"recommendation\":\"检查输入来源\"}\n```");

        assertEquals("CONDITIONAL", decision.getVerdict());
        assertEquals(0.81, decision.getConfidence());
        assertEquals(12, decision.getEvidenceLines().get(0));
    }

    @Test
    void extractsJsonObjectFromExplanatoryText() {
        AiReviewDecision decision = provider.parseDecision("审核结果如下：\n{\"verdict\":\"CONFIRMED\","
                + "\"confidence\":0.9,\"reason\":\"证据成立\",\"evidenceLines\":[],"
                + "\"missingEvidence\":[],\"recommendation\":\"整改\"}\n以上为复核结果");

        assertEquals("CONFIRMED", decision.getVerdict());
        assertEquals(0.9, decision.getConfidence());
    }

    @Test
    void identifiesTruncatedJson() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> provider.parseDecision("{\"verdict\":\"CONFIRMED\""));
        assertTrue(error.getMessage().contains("JSON 对象不完整"));
    }

    @Test
    void rejectsUnknownVerdict() {
        assertThrows(IllegalStateException.class, () -> provider.parseDecision(
                "{\"verdict\":\"SAFE\",\"confidence\":1,\"reason\":\"x\"}"));
    }

    @Test
    void callsOpenAiCompatibleEndpointWithOnlyProvidedRedactedEvidence() {
        CodeSentryProperties properties = new CodeSentryProperties();
        properties.getAi().setEnabled(true);
        properties.getAi().setBaseUrl("http://ai.local/v1");
        properties.getAi().setModel("review-model");
        properties.getAi().setThinkingMode("disabled");
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> requestEndpoint = new AtomicReference<>();
        AiReviewHttpClient client = (endpoint, key, body, timeout) -> {
            requestEndpoint.set(endpoint);
            requestBody.set(body);
            return "{\"choices\":[{\"message\":{\"content\":\"{\\\"verdict\\\":\\\"CONFIRMED\\\","
                    + "\\\"confidence\\\":0.9,\\\"reason\\\":\\\"evidence\\\",\\\"evidenceLines\\\":[1],"
                    + "\\\"missingEvidence\\\":[],\\\"recommendation\\\":\\\"fix\\\"}\"}}]}";
        };
        OpenAiCompatibleReviewProvider actual = new OpenAiCompatibleReviewProvider(
                properties, new ObjectMapper(), client);
        AiReviewEvidence evidence = new AiReviewEvidence();
        evidence.setFindingId(1L); evidence.setFilePath("src/App.java"); evidence.setCodeSnippet("<REDACTED>");

        AiReviewDecision decision = actual.review(evidence, "PRIMARY", null);

        assertEquals("CONFIRMED", decision.getVerdict());
        assertEquals("http://ai.local/v1/chat/completions", requestEndpoint.get());
        org.junit.jupiter.api.Assertions.assertFalse(requestBody.get().contains("raw-secret"));
        assertTrue(requestBody.get().contains("\"response_format\":{\"type\":\"json_object\"}"));
        assertTrue(requestBody.get().contains("\"thinking\":{\"type\":\"disabled\"}"));
    }
}
