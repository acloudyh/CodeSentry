package io.codesentry.ai.service;

import io.codesentry.ai.dto.AiReviewConfigurationRequest;
import io.codesentry.ai.dto.AiReviewConfigurationView;
import io.codesentry.ai.dto.AiToolConfigurationRequest;
import io.codesentry.ai.model.AiToolConfiguration;
import io.codesentry.ai.provider.AiReviewProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiReviewConfigurationServiceTest {
    @Test
    void legacyConfigurationEndpointReflectsActiveDatabaseTool() {
        AiToolConfigurationService tools = mock(AiToolConfigurationService.class);
        AiReviewProvider provider = mock(AiReviewProvider.class);
        AiToolConfiguration active = tool();
        when(tools.active()).thenReturn(active);
        when(provider.providerName()).thenReturn("OPENAI_COMPATIBLE");

        AiReviewConfigurationView result = new AiReviewConfigurationService(tools, provider).configuration();

        assertTrue(result.isEnabled());
        assertEquals("https://api.deepseek.com", result.getBaseUrl());
        assertEquals("deepseek-v4pro", result.getModel());
        assertTrue(result.isApiKeyConfigured());
    }

    @Test
    void legacySettingsUpdateDelegatesToDatabaseTool() {
        AiToolConfigurationService tools = mock(AiToolConfigurationService.class);
        AiReviewProvider provider = mock(AiReviewProvider.class);
        AiToolConfiguration active = tool();
        when(tools.active()).thenReturn(active);
        AiReviewConfigurationRequest request = new AiReviewConfigurationRequest();
        request.setEnabled(true); request.setBaseUrl(" https://api.deepseek.com ");
        request.setModel(" deepseek-chat "); request.setApiKey("new-secret");

        new AiReviewConfigurationService(tools, provider).configure(request);

        verify(tools).update(argThat(id -> id == 7L), argThat((AiToolConfigurationRequest value) ->
                "https://api.deepseek.com".equals(value.getBaseUrl())
                        && "deepseek-chat".equals(value.getModel())
                        && "new-secret".equals(value.getApiKey())
                        && value.isDefaultTool()));
    }

    private AiToolConfiguration tool() {
        AiToolConfiguration value = new AiToolConfiguration();
        value.setId(7L); value.setName("DeepSeek"); value.setProvider("DEEPSEEK");
        value.setBaseUrl("https://api.deepseek.com"); value.setModel("deepseek-v4pro");
        value.setApiKeyCiphertext("encrypted"); value.setEnabled(true); value.setDefaultTool(true);
        return value;
    }
}
