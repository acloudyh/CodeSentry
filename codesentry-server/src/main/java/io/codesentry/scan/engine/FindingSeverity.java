package io.codesentry.scan.engine;

public enum FindingSeverity {
  CRITICAL,
  HIGH,
  MEDIUM,
  LOW,
  INFO;

  public static FindingSeverity from(String value) {
    if (value == null) {
      return MEDIUM;
    }
    String normalized = value.trim().toUpperCase();
    if ("ERROR".equals(normalized)) {
      return HIGH;
    }
    if ("WARNING".equals(normalized) || "WARN".equals(normalized)) {
      return MEDIUM;
    }
    try {
      return FindingSeverity.valueOf(normalized);
    } catch (IllegalArgumentException exception) {
      return MEDIUM;
    }
  }
}
