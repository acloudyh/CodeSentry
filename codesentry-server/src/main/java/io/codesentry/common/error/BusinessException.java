package io.codesentry.common.error;

import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {

  private final HttpStatus status;
  private final String code;

  public BusinessException(HttpStatus status, String message) {
    this(status, "HTTP_" + status.value(), message);
  }

  /** 为稳定业务错误码预留显式构造器；旧调用保持兼容。 */
  public BusinessException(HttpStatus status, String code, String message) {
    super(message);
    if (!status.isError()) throw new IllegalArgumentException("业务异常必须使用 4xx 或 5xx 状态码");
    if (code == null || !code.matches("[A-Z][A-Z0-9_]{1,63}"))
      throw new IllegalArgumentException("错误码不合法");
    this.status = status;
    this.code = code;
  }

  public String getCode() {
    return code;
  }

  public HttpStatus getStatus() {
    return status;
  }
}
