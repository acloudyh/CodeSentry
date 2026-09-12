package io.codesentry.ai.dto;

public class AiReviewConfigurationView {
    private final boolean enabled;
    private final String provider;
    private final String model;

    public AiReviewConfigurationView(boolean enabled, String provider, String model) {
        this.enabled = enabled;
        this.provider = provider;
        this.model = model;
    }

    public boolean isEnabled() { return enabled; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
}
