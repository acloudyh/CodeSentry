package io.codesentry.process;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证秘密不会进入诊断快照，同时不改变真正交给扫描结果解析器的输出。 */
class DiagnosticRedactorTest {
  @Test
  void hidesCredentialArgumentsUrlsAndStructuredSecrets() {
    CommandTrace trace =
        new CommandTrace(
            List.of(
                "tool", "--password", "private-pass", "https://user:private-url@example.test/repo"),
            "/workspace",
            0,
            "{\"Secret\":\"private-secret\"} token=private-token\nAuthorization: Bearer private-auth",
            LocalDateTime.now(),
            LocalDateTime.now());
    assertFalse(trace.getCommandLine().contains("private-"));
    assertFalse(trace.getOutput().contains("private-"));
    assertTrue(trace.getCommandLine().contains("example.test"));
    assertNull(DiagnosticRedactor.text(null));
    assertEquals("normal output", DiagnosticRedactor.text("normal output"));
  }

  @Test
  void commandResultRemainsParsableWhileTraceIsRedacted() {
    try {
      CommandResult result =
          new CommandExecutor()
              .execute(
                  Path.of("."),
                  List.of("/usr/bin/printf", "token=private-value"),
                  Duration.ofSeconds(5));
      assertTrue(result.getOutput().contains("private-value"));
      assertFalse(CommandTraceHolder.get().getOutput().contains("private-value"));
    } finally {
      CommandTraceHolder.clear();
    }
  }
}
