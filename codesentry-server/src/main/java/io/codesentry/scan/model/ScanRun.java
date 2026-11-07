package io.codesentry.scan.model;

import java.time.LocalDateTime;

public class ScanRun {

    private Long id;
    private Long projectId;
    private Long sourceReportRunId;
    public Long getSourceReportRunId() { return sourceReportRunId; }
    public void setSourceReportRunId(Long value) { sourceReportRunId = value; }

    private ScanMode scanMode;
    private WorkspaceMode workspaceMode;
    private String artifactPath;
    public String getArtifactPath() { return artifactPath; }
    public void setArtifactPath(String value) { artifactPath = value; }
    private String scanRef;
    private String baselineRef;
    private String ruleProfile;
    private String scanCommit;
    private String baselineHeadCommit;
    private String mergeBaseCommit;
    private Boolean repositoryDirty;
    private ScanRunStatus status;
    private Integer totalFindings;
    private Integer newFindings;
    private Integer fixedFindings;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public ScanMode getScanMode() {
        return scanMode;
    }

    public void setScanMode(ScanMode scanMode) {
        this.scanMode = scanMode;
    }

    public WorkspaceMode getWorkspaceMode() {
        return workspaceMode;
    }

    public void setWorkspaceMode(WorkspaceMode workspaceMode) {
        this.workspaceMode = workspaceMode;
    }

    public String getScanRef() {
        return scanRef;
    }

    public void setScanRef(String scanRef) {
        this.scanRef = scanRef;
    }

    public String getBaselineRef() {
        return baselineRef;
    }

    public void setBaselineRef(String baselineRef) {
        this.baselineRef = baselineRef;
    }

    public String getRuleProfile() { return ruleProfile; }

    public void setRuleProfile(String ruleProfile) { this.ruleProfile = ruleProfile; }

    public String getScanCommit() {
        return scanCommit;
    }

    public void setScanCommit(String scanCommit) {
        this.scanCommit = scanCommit;
    }

    public String getBaselineHeadCommit() {
        return baselineHeadCommit;
    }

    public void setBaselineHeadCommit(String baselineHeadCommit) {
        this.baselineHeadCommit = baselineHeadCommit;
    }

    public String getMergeBaseCommit() {
        return mergeBaseCommit;
    }

    public void setMergeBaseCommit(String mergeBaseCommit) {
        this.mergeBaseCommit = mergeBaseCommit;
    }

    public Boolean getRepositoryDirty() {
        return repositoryDirty;
    }

    public void setRepositoryDirty(Boolean repositoryDirty) {
        this.repositoryDirty = repositoryDirty;
    }

    public ScanRunStatus getStatus() {
        return status;
    }

    public void setStatus(ScanRunStatus status) {
        this.status = status;
    }

    public Integer getTotalFindings() {
        return totalFindings;
    }

    public void setTotalFindings(Integer totalFindings) {
        this.totalFindings = totalFindings;
    }

    public Integer getNewFindings() {
        return newFindings;
    }

    public void setNewFindings(Integer newFindings) {
        this.newFindings = newFindings;
    }

    public Integer getFixedFindings() {
        return fixedFindings;
    }

    public void setFixedFindings(Integer fixedFindings) {
        this.fixedFindings = fixedFindings;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
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
