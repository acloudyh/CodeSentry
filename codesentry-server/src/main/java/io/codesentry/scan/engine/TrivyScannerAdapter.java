package io.codesentry.scan.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.process.CommandExecutor;
import io.codesentry.process.CommandResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class TrivyScannerAdapter extends AbstractCliScannerAdapter {

  public TrivyScannerAdapter(
      CommandExecutor commandExecutor, ObjectMapper objectMapper, CodeSentryProperties properties) {
    super(commandExecutor, objectMapper, properties);
  }

  @Override
  public String name() {
    return "Trivy";
  }

  @Override
  public boolean enabled() {
    return properties.getScanners().getTrivy().isEnabled();
  }

  @Override
  protected String executable() {
    return properties.getScanners().getTrivy().getExecutable();
  }

  @Override
  public List<NormalizedFinding> scan(ScanContext context) {
    Path report = context.getOutputDirectory().resolve("trivy-" + context.getLabel() + ".json");
    try {
      Files.createDirectories(context.getOutputDirectory());
    } catch (Exception exception) {
      throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "无法创建 Trivy 报告目录");
    }

    String scanners = properties.getScanners().getTrivy().getConfig();
    if (scanners == null || scanners.isBlank()) scanners = "vuln,secret";
    if (context.getWorkspaceMode() == io.codesentry.scan.model.WorkspaceMode.JAR_UPLOAD)
      scanners = "vuln";
    int timeoutSeconds = Math.max(1, properties.getScanners().getTrivy().getTimeoutSeconds());
    scanTarget(context.getWorkspace(), report, scanners, timeoutSeconds, context);

    List<NormalizedFinding> findings = findings(readJson(report));
    Map<String, String> aliases = prepareArchiveAliases(context);
    if (!aliases.isEmpty()) {
      Path aliasRoot =
          context.getOutputDirectory().resolve("trivy-archive-aliases-" + context.getLabel());
      Path aliasReport =
          context
              .getOutputDirectory()
              .resolve("trivy-archive-aliases-" + context.getLabel() + ".json");
      scanTarget(aliasRoot, aliasReport, "vuln", timeoutSeconds, context);
      List<NormalizedFinding> aliasFindings = findings(readJson(aliasReport));
      aliasFindings.forEach(finding -> restoreArchivePath(finding, aliases));
      findings.addAll(aliasFindings);
    }
    return findings;
  }

  private void scanTarget(
      Path target, Path report, String scanners, int timeoutSeconds, ScanContext context) {
    // rootfs expands packaged Java artifacts on Trivy 0.62, while filesystem may
    // report zero language files for legacy WEB-INF/lib directories.
    List<String> command =
        new ArrayList<>(
            List.of(
                executable(),
                "rootfs",
                "--format",
                "json",
                "--output",
                report.toString(),
                "--scanners",
                scanners,
                "--timeout",
                timeoutSeconds + "s"));
    for (String excluded : properties.getScanning().getExcludedGlobs()) {
      command.add("--skip-files");
      command.add(excluded);
    }
    if (context.getNetworkMode() != io.codesentry.scan.model.NetworkMode.ONLINE
        || properties.getScanners().getTrivy().isOfflineScan()) {
      command.add("--offline-scan");
      command.add("--skip-db-update");
      command.add("--skip-java-db-update");
    }
    command.add(target.toString());
    CommandResult result = execute(target, command, Duration.ofSeconds(timeoutSeconds + 15L));
    if (!result.isSuccess()) {
      boolean timeout =
          result.getOutput() != null && result.getOutput().contains("context deadline exceeded");
      throw new BusinessException(
          timeout ? HttpStatus.REQUEST_TIMEOUT : HttpStatus.INTERNAL_SERVER_ERROR,
          timeout
              ? "Trivy 扫描超过 " + timeoutSeconds + " 秒，请检查目录规模或调整 CODESENTRY_TRIVY_TIMEOUT_SECONDS"
              : "Trivy 扫描执行失败（退出码 " + result.getExitCode() + "），请查看执行日志");
    }
  }

  List<NormalizedFinding> findings(JsonNode root) {
    List<NormalizedFinding> findings = new ArrayList<>();
    for (JsonNode resultNode : root.path("Results")) {
      String target = text(resultNode, "Target");
      appendVulnerabilities(findings, resultNode.path("Vulnerabilities"), target);
      appendMisconfigurations(findings, resultNode.path("Misconfigurations"), target);
      appendSecrets(findings, resultNode.path("Secrets"), target);
    }
    return findings;
  }

  Map<String, String> prepareArchiveAliases(ScanContext context) {
    Path aliases =
        context.getOutputDirectory().resolve("trivy-archive-aliases-" + context.getLabel());
    Map<String, String> originals = new LinkedHashMap<>();
    try (Stream<Path> paths = Files.walk(context.getWorkspace())) {
      List<Path> renamedArchives =
          paths
              .filter(Files::isRegularFile)
              .filter(this::isRenamedJar)
              .sorted()
              .collect(Collectors.toList());
      if (renamedArchives.isEmpty()) return originals;
      Files.createDirectories(aliases);
      int index = 0;
      for (Path source : renamedArchives) {
        String aliasName = String.format("%03d-%s.jar", index++, source.getFileName());
        Files.copy(source, aliases.resolve(aliasName), StandardCopyOption.REPLACE_EXISTING);
        originals.put(aliasName, context.getWorkspace().relativize(source).toString());
      }
      return originals;
    } catch (Exception exception) {
      throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "无法准备非标准 JAR 依赖扫描目录");
    }
  }

  private boolean isRenamedJar(Path path) {
    return path.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.jar\\d+$");
  }

  private void restoreArchivePath(NormalizedFinding finding, Map<String, String> aliases) {
    String path = finding.getFilePath();
    if (path == null) return;
    aliases.forEach(
        (alias, original) -> {
          if (path.contains(alias)) finding.setFilePath(original);
        });
  }

  private void appendVulnerabilities(
      List<NormalizedFinding> findings, JsonNode items, String target) {
    for (JsonNode item : items) {
      NormalizedFinding finding = base(item, "VULNERABILITY", target);
      finding.setFilePath(defaultValue(text(item, "PkgPath"), target));
      finding.setRuleId(text(item, "VulnerabilityID"));
      finding.setTitle(text(item, "Title"));
      finding.setDescription(text(item, "Description"));
      finding.setRecommendation(
          io.codesentry.finding.service.DependencyRemediationAdvice.fromScan(
              text(item, "PkgName"),
              text(item, "InstalledVersion"),
              text(item, "VulnerabilityID"),
              text(item, "FixedVersion"),
              text(item, "PrimaryURL")));
      finding.setCodeSnippet(text(item, "PkgName") + "@" + text(item, "InstalledVersion"));
      findings.add(finding);
    }
  }

  private void appendMisconfigurations(
      List<NormalizedFinding> findings, JsonNode items, String target) {
    for (JsonNode item : items) {
      NormalizedFinding finding = base(item, "MISCONFIGURATION", target);
      finding.setRuleId(text(item, "ID"));
      finding.setTitle(text(item, "Title"));
      finding.setDescription(text(item, "Description"));
      finding.setRecommendation(text(item, "Resolution"));
      finding.setStartLine(integer(item.path("CauseMetadata").path("StartLine"), "Number"));
      findings.add(finding);
    }
  }

  private void appendSecrets(List<NormalizedFinding> findings, JsonNode items, String target) {
    for (JsonNode item : items) {
      NormalizedFinding finding = base(item, "SECRET", target);
      finding.setRuleId(text(item, "RuleID"));
      finding.setTitle(text(item, "Title"));
      finding.setDescription("检测到疑似敏感信息，内容已脱敏。");
      finding.setRecommendation("删除硬编码敏感信息并轮换凭据。");
      finding.setStartLine(integer(item, "StartLine"));
      finding.setEndLine(integer(item, "EndLine"));
      finding.setCodeSnippet("[REDACTED]");
      findings.add(finding);
    }
  }

  private NormalizedFinding base(JsonNode item, String category, String target) {
    NormalizedFinding finding = new NormalizedFinding();
    finding.setScanner(name());
    finding.setCategory(category);
    finding.setSeverity(FindingSeverity.from(text(item, "Severity")));
    finding.setFilePath(defaultValue(text(item, "Target"), target));
    return finding;
  }

  private String defaultValue(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
