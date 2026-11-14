package io.codesentry.scan.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.process.CommandExecutor;
import io.codesentry.process.CommandResult;
import io.codesentry.project.model.ScanProject;
import io.codesentry.rule.service.RuleProfileService;
import io.codesentry.scan.model.ScanMode;
import io.codesentry.scan.model.WorkspaceMode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SemgrepScannerAdapterTest {
  @Test
  void availabilityChecksInstalledVersionWithoutNetworkOrMetrics() {
    CommandExecutor commands = mock(CommandExecutor.class);
    when(commands.execute(any(), anyList(), any())).thenReturn(new CommandResult(0, "1.119.0"));
    SemgrepScannerAdapter adapter =
        new SemgrepScannerAdapter(
            commands,
            new ObjectMapper(),
            new CodeSentryProperties(),
            mock(RuleProfileService.class));
    assertTrue(adapter.available());
    verify(commands)
        .execute(
            eq(null),
            eq(List.of("semgrep", "--disable-version-check", "--metrics=off", "--version")),
            eq(java.time.Duration.ofSeconds(30)));
  }

  @Test
  void availabilityReportsActualLocalFailureAndDoesNotHideCancellation() {
    CommandExecutor commands = mock(CommandExecutor.class);
    when(commands.execute(any(), anyList(), any()))
        .thenThrow(
            new io.codesentry.common.error.BusinessException(
                org.springframework.http.HttpStatus.REQUEST_TIMEOUT, "本地命令执行超时"));
    SemgrepScannerAdapter adapter =
        new SemgrepScannerAdapter(
            commands,
            new ObjectMapper(),
            new CodeSentryProperties(),
            mock(RuleProfileService.class));
    org.junit.jupiter.api.Assertions.assertFalse(adapter.available());
    assertTrue(adapter.unavailabilityReason().contains("本地命令执行超时"));
    org.mockito.Mockito.doThrow(new java.util.concurrent.CancellationException("已停止"))
        .when(commands)
        .execute(any(), anyList(), any());
    org.junit.jupiter.api.Assertions.assertThrows(
        java.util.concurrent.CancellationException.class, adapter::available);
  }

  @TempDir Path temp;

  @Test
  void exclusionsAndStableRuleIdsArePassedToSemgrep() {
    CommandExecutor commands = mock(CommandExecutor.class);
    RuleProfileService profiles = mock(RuleProfileService.class);
    CodeSentryProperties properties = new CodeSentryProperties();
    ObjectMapper json = new ObjectMapper();
    when(profiles.semgrepConfigs("LOCAL_OWASP")).thenReturn(List.of("rules.yml"));
    when(commands.execute(any(), anyList(), any())).thenReturn(new CommandResult(0, ""));
    SemgrepScannerAdapter adapter =
        new SemgrepScannerAdapter(commands, json, properties, profiles) {
          @Override
          protected JsonNode readJson(Path report) {
            JsonNode root = json.createObjectNode();
            ((com.fasterxml.jackson.databind.node.ObjectNode) root).putArray("results");
            return root;
          }
        };
    ScanContext context =
        new ScanContext(
            1L,
            new ScanProject(),
            ScanMode.FULL,
            WorkspaceMode.BRANCH_SNAPSHOT,
            temp,
            temp,
            temp,
            "commit",
            null,
            "current",
            "LOCAL_OWASP");

    assertTrue(adapter.scan(context).isEmpty());

    verify(commands)
        .execute(
            eq(temp),
            org.mockito.ArgumentMatchers.argThat(
                args ->
                    args.contains("--no-rewrite-rule-ids")
                        && args.contains("--exclude")
                        && args.contains("**/*.png")),
            any());
  }

  @Test
  void evalRulesAreSuppressedButOtherFindingsRemain() {
    CommandExecutor commands = mock(CommandExecutor.class);
    RuleProfileService profiles = mock(RuleProfileService.class);
    CodeSentryProperties properties = new CodeSentryProperties();
    ObjectMapper json = new ObjectMapper();
    when(profiles.semgrepConfigs("LOCAL_OWASP")).thenReturn(List.of("rules.yml"));
    when(commands.execute(any(), anyList(), any())).thenReturn(new CommandResult(0, ""));
    SemgrepScannerAdapter adapter =
        new SemgrepScannerAdapter(commands, json, properties, profiles) {
          @Override
          protected JsonNode readJson(Path report) {
            com.fasterxml.jackson.databind.node.ObjectNode root = json.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode results = root.putArray("results");
            addFinding(results, "codesentry.javascript.eval", "eval 风险");
            addFinding(
                results,
                "javascript.lang.security.audit.eval-detected.eval-detected",
                "社区 eval 风险");
            addFinding(results, "codesentry.jsp.request-to-file", "文件路径风险");
            return root;
          }

          private void addFinding(
              com.fasterxml.jackson.databind.node.ArrayNode results, String rule, String message) {
            com.fasterxml.jackson.databind.node.ObjectNode item = results.addObject();
            item.put("check_id", rule).put("path", "test.jsp");
            item.putObject("start").put("line", 1);
            item.putObject("end").put("line", 1);
            com.fasterxml.jackson.databind.node.ObjectNode extra = item.putObject("extra");
            extra.put("message", message).put("severity", "ERROR").put("lines", "test");
            extra.putObject("metadata").put("category", "security").put("fix", "fix");
          }
        };
    ScanContext context =
        new ScanContext(
            1L,
            new ScanProject(),
            ScanMode.FULL,
            WorkspaceMode.BRANCH_SNAPSHOT,
            temp,
            temp,
            temp,
            "commit",
            null,
            "current",
            "LOCAL_OWASP");

    List<NormalizedFinding> findings = adapter.scan(context);

    assertEquals(1, findings.size());
    assertEquals("codesentry.jsp.request-to-file", findings.get(0).getRuleId());
  }
}
