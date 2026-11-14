package io.codesentry.common.http;

import java.util.UUID;
import org.slf4j.MDC;

/** 请求关联标识；测试及非 HTTP 调用也生成有效 ID，不依赖不可信的客户端文本。 */
public final class RequestTrace {
  public static final String HEADER = "X-Trace-Id";

  private RequestTrace() {}

  public static String current() {
    String traceId = MDC.get("traceId");
    return traceId == null ? UUID.randomUUID().toString().replace("-", "") : traceId;
  }

  public static String resolve(String incoming) {
    return incoming != null && incoming.matches("[a-f0-9]{32}")
        ? incoming
        : UUID.randomUUID().toString().replace("-", "");
  }
}
