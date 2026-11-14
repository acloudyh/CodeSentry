package io.codesentry.common.error;

import io.codesentry.common.http.RequestTrace;
import java.util.List;
import org.springframework.http.HttpStatus;

/** RFC 9457 错误结构；保留 v1 message/success，避免旧页面丢失错误提示。 */
public final class ApiProblem {
  public final String type;
  public final String title;
  public final int status;
  public final String detail;
  public final String instance;
  public final String code;
  public final String traceId;
  public final List<FieldViolation> errors;
  public final boolean success = false;
  public final String message;

  public ApiProblem(
      HttpStatus status, String code, String detail, String instance, List<FieldViolation> errors) {
    this.type =
        "urn:codesentry:problem:" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    this.title = status.getReasonPhrase();
    this.status = status.value();
    this.detail = detail;
    this.message = detail;
    this.instance = instance;
    this.code = code;
    this.traceId = RequestTrace.current();
    this.errors = List.copyOf(errors);
  }

  /** 只包含字段名和校验消息，不序列化被拒绝值，防止密码/令牌出现在错误体中。 */
  public static final class FieldViolation {
    public final String field;
    public final String message;

    public FieldViolation(String field, String message) {
      this.field = field;
      this.message = message;
    }
  }
}
