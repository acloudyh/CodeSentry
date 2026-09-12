package io.codesentry.scan.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.process.CommandExecutor;
import io.codesentry.process.CommandResult;
import io.codesentry.rule.service.RuleProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class SemgrepScannerAdapter extends AbstractCliScannerAdapter {

    private final RuleProfileService ruleProfileService;

    public SemgrepScannerAdapter(CommandExecutor commandExecutor,
                                 ObjectMapper objectMapper,
                                 CodeSentryProperties properties,
                                 RuleProfileService ruleProfileService) {
        super(commandExecutor, objectMapper, properties);
        this.ruleProfileService = ruleProfileService;
    }

    @Override
    public String name() {
        return "Semgrep";
    }

    @Override
    public boolean enabled() {
        return properties.getScanners().getSemgrep().isEnabled();
    }

    @Override
    protected String executable() {
        return properties.getScanners().getSemgrep().getExecutable();
    }

    @Override
    public List<NormalizedFinding> scan(ScanContext context) {
        Path report = context.getOutputDirectory().resolve("semgrep-" + context.getLabel() + ".json");
        try {
            Files.createDirectories(context.getOutputDirectory());
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "无法创建 Semgrep 报告目录");
        }

        List<String> command = new ArrayList<>();
        command.add(executable());
        command.add("scan");
        for (String config : ruleProfileService.semgrepConfigs(context.getRuleProfile())) {
            command.add("--config");
            command.add(config);
        }
        command.add("--json");
        command.add("--output");
        command.add(report.toString());
        command.add(context.getWorkspace().toString());
        CommandResult result = execute(context.getWorkspace(), command,
                Duration.ofMinutes(properties.getScanning().getTaskTimeoutMinutes()));
        if (result.getExitCode() != 0 && result.getExitCode() != 1) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "Semgrep 扫描执行失败");
        }

        JsonNode root = readJson(report);
        List<NormalizedFinding> findings = new ArrayList<>();
        for (JsonNode item : root.path("results")) {
            NormalizedFinding finding = new NormalizedFinding();
            finding.setScanner(name());
            finding.setRuleId(text(item, "check_id"));
            String filePath = text(item, "path");
            int startLine = integer(item.path("start"), "line");
            int endLine = integer(item.path("end"), "line");
            finding.setFilePath(filePath);
            finding.setStartLine(startLine);
            finding.setEndLine(endLine);
            JsonNode extra = item.path("extra");
            finding.setTitle(text(extra, "message"));
            finding.setDescription(text(extra, "message"));
            finding.setSeverity(FindingSeverity.from(text(extra, "severity")));
            finding.setCodeSnippet(SourceSnippetExtractor.extract(context.getWorkspace(), filePath,
                    startLine, endLine, text(extra, "lines")));
            JsonNode metadata = extra.path("metadata");
            finding.setCategory(text(metadata, "category"));
            finding.setCwe(firstText(metadata.path("cwe")));
            finding.setRecommendation(text(metadata, "fix"));
            findings.add(finding);
        }
        return findings;
    }

    private String firstText(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isArray() && value.size() > 0) {
            return value.get(0).asText();
        }
        return value.asText();
    }
}
