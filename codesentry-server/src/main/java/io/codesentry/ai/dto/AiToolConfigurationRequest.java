package io.codesentry.ai.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class AiToolConfigurationRequest {
    @NotBlank @Size(max = 120)
    private String name;
    @NotBlank @Size(max = 40)
    private String provider;
    @NotBlank @Size(max = 1000)
    private String baseUrl;
    @NotBlank @Size(max = 200)
    private String model;
    @Size(max = 4000)
    private String apiKey;
    private boolean enabled = true;
    private boolean defaultTool;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isDefaultTool() { return defaultTool; }
    public void setDefaultTool(boolean defaultTool) { this.defaultTool = defaultTool; }
}
