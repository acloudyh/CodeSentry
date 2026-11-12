package io.codesentry.business;

public class ProjectScanItem {
    public io.codesentry.project.model.ApplicationType applicationType = io.codesentry.project.model.ApplicationType.BACKEND;
    public io.codesentry.project.model.ApplicationType getApplicationType() { return applicationType; }
    public void setApplicationType(io.codesentry.project.model.ApplicationType value) { applicationType = value; }
    public String targetNodeVersion;
    public String getTargetNodeVersion() { return targetNodeVersion; }
    public void setTargetNodeVersion(String value) { targetNodeVersion=value; }
    public Long id;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long roundId;
    public Long getRoundId() { return roundId; }
    public void setRoundId(Long value) { roundId = value; }
    public Long assetId;
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long value) { assetId = value; }
    public String assetName;
    public String getAssetName() { return assetName; }
    public void setAssetName(String value) { assetName = value; }
    public String sourceType;
    public String getSourceType() { return sourceType; }
    public void setSourceType(String value) { sourceType = value; }
    public Integer targetJavaVersion;
    public Integer getTargetJavaVersion() { return targetJavaVersion; }
    public void setTargetJavaVersion(Integer value) { targetJavaVersion = value; }
    public String workspaceMode;
    public String getWorkspaceMode() { return workspaceMode; }
    public void setWorkspaceMode(String value) { workspaceMode = value; }
    public String scanRef;
    public String getScanRef() { return scanRef; }
    public void setScanRef(String value) { scanRef = value; }
    public String artifactPath;
    public String getArtifactPath() { return artifactPath; }
    public void setArtifactPath(String value) { artifactPath = value; }
    public Long runId;
    public Long getRunId() { return runId; }
    public void setRunId(Long value) { runId = value; }
    public String errorMessage;
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String value) { errorMessage = value; }
    public String status;
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public io.codesentry.scan.model.ScanRun run;
    public io.codesentry.scan.model.ScanRun getRun() { return run; }
    public void setRun(io.codesentry.scan.model.ScanRun value) { run = value; }
}
