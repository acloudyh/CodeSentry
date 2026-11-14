package io.codesentry.process;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 仅清理诊断日志；扫描器原始输出仍供解析，不能因日志脱敏丢失检测证据。 */
public final class DiagnosticRedactor {
  private static final Pattern ASSIGNMENT =
      Pattern.compile(
          "(?i)((?:password|passwd|pwd|token|api[_-]?key|secret|authorization)[\\\"']?\\s*[:=]\\s*)(?:\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'[^']*'|[^\\s,;]+)");
  private static final Pattern AUTH =
      Pattern.compile("(?i)\\b(?:Bearer|Basic)\\s+[A-Za-z0-9+/=_\\-.]+");
  private static final Pattern URL_USERINFO = Pattern.compile("(?<=://)[^/@\\s]+:[^/@\\s]+@");

  private DiagnosticRedactor() {}

  public static String text(String value) {
    if (value == null) return null;
    return URL_USERINFO
        .matcher(
            ASSIGNMENT
                .matcher(AUTH.matcher(value).replaceAll("[REDACTED]"))
                .replaceAll("$1[REDACTED]"))
        .replaceAll("[REDACTED]@");
  }

  public static List<String> command(List<String> arguments) {
    List<String> safe = new ArrayList<>();
    boolean secret = false;
    for (String value : arguments) {
      safe.add(secret ? "[REDACTED]" : text(value));
      secret =
          value != null
              && value.matches("(?i)--?(?:password|passwd|token|api[_-]?key|secret|authorization)");
    }
    return safe;
  }
}
