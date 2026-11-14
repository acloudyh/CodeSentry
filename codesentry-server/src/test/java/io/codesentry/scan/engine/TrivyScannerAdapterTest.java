package io.codesentry.scan.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.process.CommandExecutor;
import io.codesentry.process.CommandResult;
import io.codesentry.project.model.ScanProject;
import io.codesentry.scan.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrivyScannerAdapterTest {
  @TempDir Path temp;
  private final CommandExecutor commands = mock(CommandExecutor.class);
  private final CodeSentryProperties properties = new CodeSentryProperties();

  private TrivyScannerAdapter adapter() {
    ObjectMapper json = new ObjectMapper();
    return new TrivyScannerAdapter(commands, json, properties) {
      @Override
      protected JsonNode readJson(Path path) {
        return json.createObjectNode();
      }
    };
  }

  private ScanContext context() {
    return new ScanContext(
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
        "GENERAL");
  }

  @Test
  void offlineDefaultAndExplicitTimeoutArePassedToCliWithOuterGrace() {
    when(commands.execute(any(), anyList(), any())).thenReturn(new CommandResult(0, ""));
    assertTrue(adapter().scan(context()).isEmpty());
    verify(commands)
        .execute(
            eq(temp),
            argThat(
                args ->
                    args.contains("--offline-scan")
                        && args.contains("rootfs")
                        && args.contains("--skip-db-update")
                        && args.contains("--skip-java-db-update")
                        && args.contains("--timeout")
                        && args.contains("180s")
                        && args.contains("vuln,secret")
                        && args.contains("--skip-files")
                        && args.contains("**/*.png")),
            eq(Duration.ofSeconds(195)));
  }

  @Test
  void configurableTimeoutAndOnlineModeAreHonored() {
    properties.getScanners().getTrivy().setTimeoutSeconds(60);
    properties.getScanners().getTrivy().setOfflineScan(false);
    when(commands.execute(any(), anyList(), any())).thenReturn(new CommandResult(0, ""));
    ScanContext online = context();
    online.setNetworkMode(io.codesentry.scan.model.NetworkMode.ONLINE);
    adapter().scan(online);
    verify(commands)
        .execute(
            eq(temp),
            argThat(args -> !args.contains("--offline-scan") && args.contains("60s")),
            eq(Duration.ofSeconds(75)));
  }

  @Test
  void trivyDeadlineHasActionableFailureInsteadOfGenericError() {
    when(commands.execute(any(), anyList(), any()))
        .thenReturn(new CommandResult(1, "semaphore acquire: context deadline exceeded"));
    BusinessException error =
        assertThrows(BusinessException.class, () -> adapter().scan(context()));
    assertTrue(error.getMessage().contains("180 秒"));
  }

  @Test
  void renamedJarFilesAreCopiedToAStandardJarAlias() throws Exception {
    Path legacy = temp.resolve("legacy.jar37");
    Files.writeString(legacy, "archive");

    Map<String, String> aliases = adapter().prepareArchiveAliases(context());

    assertEquals(1, aliases.size());
    String alias = aliases.keySet().iterator().next();
    assertTrue(alias.endsWith(".jar"));
    assertEquals("legacy.jar37", aliases.get(alias));
    assertTrue(Files.isRegularFile(temp.resolve("trivy-archive-aliases-current").resolve(alias)));
  }

  @Test
  void dependencyVulnerabilityUsesPackagePathInsteadOfGenericJavaTarget() throws Exception {
    JsonNode report =
        new ObjectMapper()
            .readTree(
                "{\"Results\":[{\"Target\":\"Java\","
                    + "\"Vulnerabilities\":[{\"VulnerabilityID\":\"CVE-2020-10683\","
                    + "\"PkgName\":\"dom4j:dom4j\",\"PkgPath\":\"src/main/webapp/WEB-INF/lib/dom4j-1.6.1.jar\","
                    + "\"InstalledVersion\":\"1.6.1\",\"Severity\":\"HIGH\",\"Title\":\"XXE\"}]}]}");

    List<NormalizedFinding> findings = adapter().findings(report);

    assertEquals(1, findings.size());
    assertEquals("src/main/webapp/WEB-INF/lib/dom4j-1.6.1.jar", findings.get(0).getFilePath());
    assertNull(findings.get(0).getStartLine());
    assertEquals("dom4j:dom4j@1.6.1", findings.get(0).getCodeSnippet());
    assertTrue(findings.get(0).getRecommendation().contains("org.dom4j:dom4j:2.1.4"));
    assertFalse(findings.get(0).getRecommendation().contains("请查阅漏洞公告"));
  }

  @Test
  void dependencyVulnerabilityFallsBackToResultTargetWhenPackagePathIsMissing() throws Exception {
    JsonNode report =
        new ObjectMapper()
            .readTree(
                "{\"Results\":[{\"Target\":\"pom.xml\","
                    + "\"Vulnerabilities\":[{\"VulnerabilityID\":\"CVE-1\",\"PkgName\":\"a:b\","
                    + "\"InstalledVersion\":\"1\",\"Severity\":\"LOW\",\"Title\":\"test\"}]}]}");

    List<NormalizedFinding> findings = adapter().findings(report);

    assertEquals("pom.xml", findings.get(0).getFilePath());
  }
}
