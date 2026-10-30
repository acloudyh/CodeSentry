package io.codesentry.ai.dto;

import javax.validation.constraints.Size;

public class AiReviewConfigurationRequest {
    private boolean enabled;

    @Size(max = 1000)
    private String baseUrl;

    @Size(max = 200)
    private String model;

    @Size(max = 4000)
    private String apiKey;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
}
