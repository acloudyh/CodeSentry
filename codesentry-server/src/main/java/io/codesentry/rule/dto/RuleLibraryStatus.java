package io.codesentry.rule.dto;

public class RuleLibraryStatus {
  private final String state;
  private final boolean available;
  private final String version;
  private final String updatedAt;
  private final Integer ruleCount;
  private final String message;

  public RuleLibraryStatus(
      String state,
      boolean available,
      String version,
      String updatedAt,
      Integer ruleCount,
      String message) {
    this.state = state;
    this.available = available;
    this.version = version;
    this.updatedAt = updatedAt;
    this.ruleCount = ruleCount;
    this.message = message;
  }

  public String getState() {
    return state;
  }

  public boolean isAvailable() {
    return available;
  }

  public String getVersion() {
    return version;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public Integer getRuleCount() {
    return ruleCount;
  }

  public String getMessage() {
    return message;
  }
}
