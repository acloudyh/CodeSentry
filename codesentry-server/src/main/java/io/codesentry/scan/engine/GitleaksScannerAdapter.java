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
public class GitleaksScannerAdapter extends AbstractCliScannerAdapter {

    public GitleaksScannerAdapter(CommandExecutor commandExecutor,
                                  ObjectMapper objectMapper,
                                  CodeSentryProperties properties) {
        super(commandExecutor, objectMapper, properties);
    }

    @Override
    public String name() {
        return "Gitleaks";
    }

    @Override
    public boolean enabled() {
        return properties.getScanners().getGitleaks().isEnabled();
    }

    @Override
    protected String executable() {
        return properties.getScanners().getGitleaks().getExecutable();
    }

    @Override
    public List<NormalizedFinding> scan(ScanContext context) {
        Path report = context.getOutputDirectory().resolve("gitleaks-" + context.getLabel() + ".json");
        try {
            Files.createDirectories(context.getOutputDirectory());
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "无法创建 Gitleaks 报告目录");
        }

        List<String> command = List.of(executable(), "dir", "--redact", "--report-format", "json",
                "--report-path", report.toString(), context.getWorkspace().toString());
        CommandResult result = execute(context.getWorkspace(), command,
                Duration.ofMinutes(properties.getScanning().getTaskTimeoutMinutes()));
        if (result.getExitCode() != 0 && result.getExitCode() != 1) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "Gitleaks 扫描执行失败");
        }

        JsonNode root = readJson(report);
        List<NormalizedFinding> findings = new ArrayList<>();
        if (!root.isArray()) {
            return findings;
        }
        for (JsonNode item : root) {
            NormalizedFinding finding = new NormalizedFinding();
            finding.setScanner(name());
            finding.setRuleId(text(item, "RuleID"));
            finding.setSourceFingerprint(text(item, "Fingerprint"));
            finding.setCategory("SECRET");
            finding.setSeverity(FindingSeverity.HIGH);
            finding.setTitle(text(item, "Description"));
            finding.setDescription("检测到疑似敏感信息，报告内容已脱敏，请人工确认并及时轮换凭据。");
            finding.setRecommendation("删除硬编码凭据，改用环境变量或本地密钥管理服务，并轮换已暴露凭据。");
            finding.setFilePath(text(item, "File"));
            finding.setStartLine(integer(item, "StartLine"));
            finding.setEndLine(integer(item, "EndLine"));
            finding.setCodeSnippet("[REDACTED]");
            findings.add(finding);
        }
        return findings;
    }
}
