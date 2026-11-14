package io.codesentry.common.api;

import java.time.Instant;

public class ApiResponse<T> {

  private final boolean success;
  private final T data;
  private final String message;
  private final Instant timestamp;

  private ApiResponse(boolean success, T data, String message) {
    this.success = success;
    this.data = data;
    this.message = message;
    this.timestamp = Instant.now();
  }

  public static <T> ApiResponse<T> success(T data) {
    return new ApiResponse<>(true, data, null);
  }

  public boolean isSuccess() {
    return success;
  }

  public T getData() {
    return data;
  }

  public String getMessage() {
    return message;
  }

  public Instant getTimestamp() {
    return timestamp;
  }
}
