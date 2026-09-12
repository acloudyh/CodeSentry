package io.codesentry.scan.model;

import java.time.LocalDateTime;

public class ScanToolRun {

    private Long id;
    private Long scanRunId;
    private String toolName;
    private String phase;
    private String toolVersion;
    private String status;
    private Integer exitCode;
    private String commandLine;
    private String workingDirectory;
    private String rawReportPath;
    private String outputLog;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getScanRunId() {
        return scanRunId;
    }

    public void setScanRunId(Long scanRunId) {
        this.scanRunId = scanRunId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getToolVersion() {
        return toolVersion;
    }

    public void setToolVersion(String toolVersion) {
        this.toolVersion = toolVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public String getCommandLine() {
        return commandLine;
    }

    public void setCommandLine(String commandLine) {
        this.commandLine = commandLine;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public String getRawReportPath() {
        return rawReportPath;
    }

    public void setRawReportPath(String rawReportPath) {
        this.rawReportPath = rawReportPath;
    }

    public String getOutputLog() {
        return outputLog;
    }

    public void setOutputLog(String outputLog) {
        this.outputLog = outputLog;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}
