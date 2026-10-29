package io.codesentry.scan.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.process.CommandExecutor;
import io.codesentry.process.CommandResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class TrivyScannerAdapter extends AbstractCliScannerAdapter {

    public TrivyScannerAdapter(CommandExecutor commandExecutor,
                               ObjectMapper objectMapper,
                               CodeSentryProperties properties) {
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
        if (scanners == null || scanners.isBlank()) {
            scanners = "secret";
        }
        int timeoutSeconds = Math.max(1, properties.getScanners().getTrivy().getTimeoutSeconds());
        List<String> command = new ArrayList<>(List.of(executable(), "filesystem", "--format", "json", "--output",
                report.toString(), "--scanners", scanners, "--timeout", timeoutSeconds + "s"));
        if (properties.getScanners().getTrivy().isOfflineScan()) command.add("--offline-scan");
        command.add(context.getWorkspace().toString());
        CommandResult result = execute(context.getWorkspace(), command,
                Duration.ofSeconds(timeoutSeconds + 15L));
        if (!result.isSuccess()) {
            boolean timeout = result.getOutput() != null && result.getOutput().contains("context deadline exceeded");
            throw new BusinessException(timeout ? HttpStatus.REQUEST_TIMEOUT : HttpStatus.INTERNAL_SERVER_ERROR,
                    timeout ? "Trivy 扫描超过 " + timeoutSeconds + " 秒，请检查目录规模或调整 CODESENTRY_TRIVY_TIMEOUT_SECONDS"
                            : "Trivy 扫描执行失败（退出码 " + result.getExitCode() + "），请查看执行日志");
        }

        JsonNode root = readJson(report);
        List<NormalizedFinding> findings = new ArrayList<>();
        for (JsonNode resultNode : root.path("Results")) {
            String target = text(resultNode, "Target");
            appendVulnerabilities(findings, resultNode.path("Vulnerabilities"), target);
            appendMisconfigurations(findings, resultNode.path("Misconfigurations"), target);
            appendSecrets(findings, resultNode.path("Secrets"), target);
        }
        return findings;
    }

    private void appendVulnerabilities(List<NormalizedFinding> findings, JsonNode items, String target) {
        for (JsonNode item : items) {
            NormalizedFinding finding = base(item, "VULNERABILITY", target);
            finding.setRuleId(text(item, "VulnerabilityID"));
            finding.setTitle(text(item, "Title"));
            finding.setDescription(text(item, "Description"));
            finding.setRecommendation("升级到修复版本：" + defaultValue(text(item, "FixedVersion"), "请查阅漏洞公告"));
            finding.setCodeSnippet(text(item, "PkgName") + "@" + text(item, "InstalledVersion"));
            findings.add(finding);
        }
    }

    private void appendMisconfigurations(List<NormalizedFinding> findings, JsonNode items, String target) {
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
