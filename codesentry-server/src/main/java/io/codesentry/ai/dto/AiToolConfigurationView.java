package io.codesentry.ai.dto;

import io.codesentry.ai.model.AiToolConfiguration;
import java.time.LocalDateTime;

public class AiToolConfigurationView {
  private final Long id;
  private final String name;
  private final String provider;
  private final String baseUrl;
  private final String model;
  private final boolean apiKeyConfigured;
  private final boolean enabled;
  private final boolean defaultTool;
  private final LocalDateTime createdAt;
  private final LocalDateTime updatedAt;

  public AiToolConfigurationView(AiToolConfiguration value) {
    id = value.getId();
    name = value.getName();
    provider = value.getProvider();
    baseUrl = value.getBaseUrl();
    model = value.getModel();
    apiKeyConfigured =
        value.getApiKeyCiphertext() != null && !value.getApiKeyCiphertext().isBlank();
    enabled = Boolean.TRUE.equals(value.getEnabled());
    defaultTool = Boolean.TRUE.equals(value.getDefaultTool());
    createdAt = value.getCreatedAt();
    updatedAt = value.getUpdatedAt();
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getProvider() {
    return provider;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public String getModel() {
    return model;
  }

  public boolean isApiKeyConfigured() {
    return apiKeyConfigured;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public boolean isDefaultTool() {
    return defaultTool;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }
}
