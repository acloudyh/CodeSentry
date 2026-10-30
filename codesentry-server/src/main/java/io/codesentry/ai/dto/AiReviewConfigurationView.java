package io.codesentry.ai.dto;

public class AiReviewConfigurationView {
    private final boolean enabled;
    private final String provider;
    private final String model;
    private final String baseUrl;
    private final boolean apiKeyConfigured;

    public AiReviewConfigurationView(boolean enabled, String provider, String model,
                                     String baseUrl, boolean apiKeyConfigured) {
        this.enabled = enabled;
        this.provider = provider;
        this.model = model;
        this.baseUrl = baseUrl;
        this.apiKeyConfigured = apiKeyConfigured;
    }

    public boolean isEnabled() { return enabled; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public String getBaseUrl() { return baseUrl; }
    public boolean isApiKeyConfigured() { return apiKeyConfigured; }
}
