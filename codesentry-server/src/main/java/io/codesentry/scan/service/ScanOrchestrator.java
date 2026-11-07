package io.codesentry.scan.service;

import io.codesentry.config.CodeSentryProperties;
import io.codesentry.finding.service.FindingPersistenceService;
import io.codesentry.git.GitRepositoryService;
import io.codesentry.git.RepositorySnapshotService;
import io.codesentry.project.model.ProjectSourceType;
import io.codesentry.project.model.ScanProject;
import io.codesentry.project.service.ScanProjectService;
import io.codesentry.process.CommandTraceHolder;
import io.codesentry.scan.engine.FindingFingerprintService;
import io.codesentry.scan.engine.NormalizedFinding;
import io.codesentry.scan.engine.ScanContext;
import io.codesentry.scan.engine.ScannerAdapter;
import io.codesentry.scan.mapper.ScanRunMapper;
import io.codesentry.scan.model.ScanMode;
import io.codesentry.scan.model.ScanRun;
import io.codesentry.scan.model.ScanRunStatus;
import io.codesentry.scan.model.ScanToolRun;
import io.codesentry.scan.model.WorkspaceMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ScanOrchestrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScanOrchestrator.class);

    private final ScanRunMapper scanRunMapper;
    private final ScanProjectService projectService;
    private final GitRepositoryService gitRepositoryService;
    private final RepositorySnapshotService snapshotService;
    private final FindingFingerprintService fingerprintService;
    private final FindingPersistenceService findingService;
    private final ScanToolRunService toolRunService;
    private final List<ScannerAdapter> scannerAdapters;
    private final CodeSentryProperties properties;

    public ScanOrchestrator(ScanRunMapper scanRunMapper,
                            ScanProjectService projectService,
                            GitRepositoryService gitRepositoryService,
                            RepositorySnapshotService snapshotService,
                            FindingFingerprintService fingerprintService,
                            FindingPersistenceService findingService,
                            ScanToolRunService toolRunService,
                            List<ScannerAdapter> scannerAdapters,
                            CodeSentryProperties properties) {
        this.scanRunMapper = scanRunMapper;
        this.projectService = projectService;
        this.gitRepositoryService = gitRepositoryService;
        this.snapshotService = snapshotService;
        this.fingerprintService = fingerprintService;
        this.findingService = findingService;
        this.toolRunService = toolRunService;
        this.scannerAdapters = scannerAdapters;
        this.properties = properties;
    }

    public void execute(Long runId, boolean refreshRemote) {
        ScanRun run = scanRunMapper.findById(runId);
        if (run == null) {
            return;
        }
        run.setStartedAt(LocalDateTime.now());
        if (scanRunMapper.markStarted(runId, ScanRunStatus.PREPARING.name(), run.getStartedAt()) == 0) return;

        try {
            io.codesentry.process.CancellableTasks.check();
            ScanProject project = projectService.requireById(run.getProjectId());
            if (refreshRemote && project.getSourceType() == ProjectSourceType.GIT) {
                gitRepositoryService.refresh(project);
            }
            prepareRun(project, run);
            if (scanRunMapper.updatePrepared(run) == 0) return;

            Path repository = run.getWorkspaceMode() == WorkspaceMode.JAR_UPLOAD
                    ? jarPath(project, run).getParent() : gitRepositoryService.repositoryPath(project);
            Path currentWorkspace = resolveCurrentWorkspace(project, run);
            Path reportDirectory = prepareReportDirectory(runId);
            List<ScannerAdapter> enabledAdapters = scannerAdapters.stream()
                    .filter(adapter -> run.getWorkspaceMode() == WorkspaceMode.JAR_UPLOAD
                            ? List.of("Trivy", "SpotBugs-FindSecBugs").contains(adapter.name()) : adapter.enabled())
                    .collect(Collectors.toList());
            List<ScannerAdapter> activeAdapters = enabledAdapters.stream()
                    .filter(adapter -> adapter.enabled() && adapter.available())
                    .collect(Collectors.toList());
            int unavailableTools = enabledAdapters.size() - activeAdapters.size();
            enabledAdapters.stream()
                    .filter(adapter -> !activeAdapters.contains(adapter))
                    .forEach(adapter -> toolRunService.skipped(runId, adapter.name(), "扫描器不可用或版本检测失败"));

            if (activeAdapters.isEmpty()) {
                finish(run, ScanRunStatus.PARTIAL, 0, 0, 0,
                        "未检测到可用扫描器，请检查扫描器安装与启用配置");
                return;
            }

            ScanContext currentContext = new ScanContext(runId, project, run.getScanMode(), run.getWorkspaceMode(),
                    currentWorkspace, repository, reportDirectory, run.getScanCommit(), run.getMergeBaseCommit(), "current",
                    run.getRuleProfile());

            if (run.getScanMode() == ScanMode.FULL) {
                ScanBatch batch = executeAdapters(activeAdapters, currentContext);
                if (batch.successfulTools == 0) {
                    finish(run, ScanRunStatus.PARTIAL, 0, 0, 0, "所有已启用扫描器均执行失败");
                    return;
                }
                assignFingerprints(batch.current);
                Map<String, NormalizedFinding> currentMap = unique(batch.current);
                currentMap.values().forEach(finding -> {
                    io.codesentry.process.CancellableTasks.check();
                    findingService.save(project.getId(), runId, finding, "NEW");
                });
                ScanRunStatus status = unavailableTools > 0 || batch.failedTools > 0
                        ? ScanRunStatus.PARTIAL : ScanRunStatus.COMPLETED;
                finish(run, status, currentMap.size(), currentMap.size(), 0,
                        warningMessage(unavailableTools, batch.failedTools));
                return;
            }

            Path baselineWorkspace = snapshotService.exportCommit(project, run.getMergeBaseCommit(), runId, "baseline");
            ScanContext baselineContext = new ScanContext(runId, project, run.getScanMode(), WorkspaceMode.BRANCH_SNAPSHOT,
                    baselineWorkspace, repository, reportDirectory, run.getMergeBaseCommit(), run.getMergeBaseCommit(), "baseline",
                    run.getRuleProfile());
            ScanBatch batch = executePairedAdapters(activeAdapters, currentContext, baselineContext);
            if (batch.successfulTools == 0) {
                finish(run, ScanRunStatus.PARTIAL, 0, 0, 0, "增量扫描没有可用于两侧比较的扫描器结果");
                return;
            }
            assignFingerprints(batch.current);
            assignFingerprints(batch.baseline);
            persistIncremental(project.getId(), runId, batch.current, batch.baseline);

            Map<String, NormalizedFinding> currentMap = unique(batch.current);
            Map<String, NormalizedFinding> baselineMap = unique(batch.baseline);
            int newCount = (int) currentMap.keySet().stream().filter(key -> !baselineMap.containsKey(key)).count();
            int fixedCount = (int) baselineMap.keySet().stream().filter(key -> !currentMap.containsKey(key)).count();
            ScanRunStatus status = unavailableTools > 0 || batch.failedTools > 0
                    ? ScanRunStatus.PARTIAL : ScanRunStatus.COMPLETED;
            finish(run, status, currentMap.size(), newCount, fixedCount,
                    warningMessage(unavailableTools, batch.failedTools));
        } catch (Exception exception) {
            if (Thread.currentThread().isInterrupted() || exception instanceof java.util.concurrent.CancellationException) return;
            LOGGER.error("Scan run {} failed", runId, exception);
            finish(run, ScanRunStatus.FAILED, 0, 0, 0, safeMessage(exception));
        }
    }

    private void prepareRun(ScanProject project, ScanRun run) throws IOException {
        if (run.getWorkspaceMode() == WorkspaceMode.JAR_UPLOAD) {
            if (project.getSourceType() != ProjectSourceType.JAR_UPLOAD || run.getScanMode() != ScanMode.FULL) {
                throw new IllegalArgumentException("JAR 上传仅支持全量扫描");
            }
            run.setScanCommit(JarUploadService.sha256(jarPath(project, run)));
            run.setRepositoryDirty(false);
            run.setStatus(ScanRunStatus.RUNNING);
            return;
        }
        if (run.getWorkspaceMode() == WorkspaceMode.CURRENT) {
            if (project.getSourceType() != ProjectSourceType.LOCAL) {
                throw new IllegalArgumentException("远程托管仓库不支持当前工作区扫描");
            }
            run.setScanCommit(gitRepositoryService.resolveCommit(project, "HEAD"));
            run.setRepositoryDirty(gitRepositoryService.isDirty(project));
        } else {
            run.setScanCommit(gitRepositoryService.resolveCommit(project, run.getScanRef()));
            run.setRepositoryDirty(false);
        }

        if (run.getScanMode() == ScanMode.INCREMENTAL) {
            run.setBaselineHeadCommit(gitRepositoryService.resolveCommit(project, run.getBaselineRef()));
            run.setMergeBaseCommit(gitRepositoryService.mergeBase(project,
                    run.getBaselineHeadCommit(), run.getScanCommit()));
        }
        run.setStatus(ScanRunStatus.RUNNING);
    }

    private Path jarPath(ScanProject project, ScanRun run) {
        return Path.of(run.getArtifactPath() == null ? project.getLocalPath() : run.getArtifactPath()).toAbsolutePath();
    }

    private Path resolveCurrentWorkspace(ScanProject project, ScanRun run) {
        if (run.getWorkspaceMode() == WorkspaceMode.JAR_UPLOAD) return jarPath(project, run).getParent();
        if (run.getWorkspaceMode() == WorkspaceMode.CURRENT) {
            return gitRepositoryService.repositoryPath(project);
        }
        return snapshotService.exportCommit(project, run.getScanCommit(), run.getId(), "current");
    }

    private Path prepareReportDirectory(Long runId) throws IOException {
        Path path = properties.getStorage().rootPath()
                .resolve(properties.getStorage().getReportsDirectory())
                .resolve(String.valueOf(runId));
        Files.createDirectories(path);
        return path;
    }

    private ScanBatch executeAdapters(List<ScannerAdapter> adapters, ScanContext context) {
        ScanBatch batch = new ScanBatch();
        for (ScannerAdapter adapter : adapters) {
            io.codesentry.process.CancellableTasks.check();
            try {
                batch.current.addAll(scanWithLog(adapter, context));
                batch.successfulTools++;
            } catch (RuntimeException exception) {
                io.codesentry.process.CancellableTasks.check();
                batch.failedTools++;
                LOGGER.warn("Scanner {} failed for run {}: {}", adapter.name(), context.getRunId(),
                        safeMessage(exception));
            }
        }
        return batch;
    }

    private ScanBatch executePairedAdapters(List<ScannerAdapter> adapters,
                                            ScanContext currentContext,
                                            ScanContext baselineContext) {
        ScanBatch batch = new ScanBatch();
        for (ScannerAdapter adapter : adapters) {
            io.codesentry.process.CancellableTasks.check();
            try {
                List<NormalizedFinding> current = scanWithLog(adapter, currentContext);
                List<NormalizedFinding> baseline = scanWithLog(adapter, baselineContext);
                batch.current.addAll(current);
                batch.baseline.addAll(baseline);
                batch.successfulTools++;
            } catch (RuntimeException exception) {
                io.codesentry.process.CancellableTasks.check();
                batch.failedTools++;
                LOGGER.warn("Scanner {} failed during paired scan for run {}: {}", adapter.name(),
                        currentContext.getRunId(), safeMessage(exception));
            }
        }
        return batch;
    }

    private List<NormalizedFinding> scanWithLog(ScannerAdapter adapter, ScanContext context) {
        io.codesentry.process.CancellableTasks.check();
        ScanToolRun toolRun = toolRunService.start(context.getRunId(), adapter.name(), context.getLabel());
        Path reportPath = reportPath(context, adapter);
        CommandTraceHolder.clear();
        CommandTraceHolder.listen(trace -> toolRunService.updateRunningLog(toolRun, trace));
        try {
            List<NormalizedFinding> findings = adapter.scan(context);
            toolRunService.finishSuccess(toolRun, CommandTraceHolder.get(), reportPath, findings.size());
            return findings;
        } catch (RuntimeException exception) {
            toolRunService.finishFailure(toolRun, CommandTraceHolder.get(), reportPath, safeMessage(exception));
            throw exception;
        } finally {
            CommandTraceHolder.clear();
        }
    }

    private Path reportPath(ScanContext context, ScannerAdapter adapter) {
        return context.getOutputDirectory()
                .resolve(adapter.name().toLowerCase() + "-" + context.getLabel() + "." + adapter.reportExtension());
    }

    private void assignFingerprints(List<NormalizedFinding> findings) {
        for (NormalizedFinding finding : findings) {
            if (finding.getSeverity() == null) {
                finding.setSeverity(io.codesentry.scan.engine.FindingSeverity.MEDIUM);
            }
            finding.setFingerprint(fingerprintService.calculate(finding));
        }
    }

    private void persistIncremental(Long projectId, Long runId,
                                    List<NormalizedFinding> current,
                                    List<NormalizedFinding> baseline) {
        Map<String, NormalizedFinding> currentMap = unique(current);
        Map<String, NormalizedFinding> baselineMap = unique(baseline);

        currentMap.forEach((fingerprint, finding) -> {
            io.codesentry.process.CancellableTasks.check();
            findingService.save(projectId, runId, finding, baselineMap.containsKey(fingerprint) ? "EXISTING" : "NEW");
        });
        baselineMap.forEach((fingerprint, finding) -> {
            io.codesentry.process.CancellableTasks.check();
            if (!currentMap.containsKey(fingerprint)) {
                findingService.save(projectId, runId, finding, "FIXED");
            }
        });
    }

    private Map<String, NormalizedFinding> unique(List<NormalizedFinding> findings) {
        Map<String, NormalizedFinding> values = new LinkedHashMap<>();
        for (NormalizedFinding finding : findings) {
            String family = fingerprintService.semanticFamily(finding.getRuleId());
            String key = finding.getFingerprint();
            if (family != null) {
                String location = semanticLocation(finding, family);
                key = "semantic|" + normalize(finding.getScanner()) + "|" + family + "|"
                        + normalizePath(finding.getFilePath()) + "|" + location;
            }
            NormalizedFinding existing = values.get(key);
            if (existing == null) {
                values.put(key, finding);
            }
            // Duplicate scanner hits are intentionally discarded. The first finding keeps
            // its original fingerprint so historical triage remains reusable.
        }
        return values;
    }

    private String semanticLocation(NormalizedFinding finding, String family) {
        if ("cookie-security-attributes".equals(family) && finding.getStartLine() != null) {
            // Semgrep reports the Cookie constructor and addCookie call on adjacent lines.
            return "cookie-block-" + Math.max(0, (finding.getStartLine() - 3) / 10);
        }
        return String.valueOf(finding.getStartLine()) + "-" + String.valueOf(finding.getEndLine());
    }

    private String normalizePath(String value) {
        return normalize(value).replace('\\', '/').replaceAll("^\\./", "");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private void finish(ScanRun run, ScanRunStatus status, int total, int added, int fixed, String error) {
        run.setStatus(status);
        run.setTotalFindings(total);
        run.setNewFindings(added);
        run.setFixedFindings(fixed);
        run.setErrorMessage(error);
        run.setFinishedAt(LocalDateTime.now());
        scanRunMapper.finish(run);
    }

    private String warningMessage(int unavailableTools, int failedTools) {
        if (unavailableTools == 0 && failedTools == 0) {
            return null;
        }
        return String.format("扫描部分完成：%d 个工具不可用，%d 个工具执行失败", unavailableTools, failedTools);
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private static class ScanBatch {
        private final List<NormalizedFinding> current = new ArrayList<>();
        private final List<NormalizedFinding> baseline = new ArrayList<>();
        private int successfulTools;
        private int failedTools;
    }
}
