package io.codesentry.common.error;

import java.util.regex.Pattern;

/** HTTP 错误的最后一道脱敏边界，避免业务异常夹带底层 SQL、内网地址或凭据。 */
public final class SafeErrorMessage {
  private static final Pattern INTERNAL =
      Pattern.compile(
          "(?i)(?:jdbc:|java\\.[a-z]+\\.[a-z]+Exception|(?:select|insert|update|delete)\\s+.+(?:from|into|set)|(?:\\d{1,3}\\.){3}\\d{1,3}|/(?:Users|home|var|etc)/|[A-Z]:\\\\)");
  private static final Pattern SECRET =
      Pattern.compile(
          "(?i)((?:password|passwd|pwd|token|api[-_ ]?key|authorization|secret|密码|令牌)\\s*[:=]\\s*)(?:Bearer\\s+)?[^\\s,;；]+|(?i)(?<=://)[^/@\\s]+:[^/@\\s]+@");

  private SafeErrorMessage() {}

  public static String clean(String message, String fallback) {
    if (message == null || message.isBlank() || INTERNAL.matcher(message).find()) return fallback;
    return SECRET.matcher(message).replaceAll("[REDACTED]").replaceAll("[\\r\\n\\t]", " ");
  }
}
