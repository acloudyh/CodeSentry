package io.codesentry.scan.dto;

import io.codesentry.scan.model.ScanMode;
import io.codesentry.scan.model.WorkspaceMode;

import javax.validation.constraints.NotNull;

public class CreateScanRunRequest {

    @NotNull(message = "项目不能为空")
    private Long projectId;
    private Long sourceReportRunId;
    public Long getSourceReportRunId() { return sourceReportRunId; }
    public void setSourceReportRunId(Long value) { sourceReportRunId = value; }


    @NotNull(message = "扫描模式不能为空")
    private ScanMode scanMode;

    @NotNull(message = "工作区模式不能为空")
    private WorkspaceMode workspaceMode;

    private String scanRef;
    private String baselineRef;
    private boolean refreshRemote = true;
    private String ruleProfile = "COMMUNITY_GENERAL";

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

    public boolean isRefreshRemote() {
        return refreshRemote;
    }

    public void setRefreshRemote(boolean refreshRemote) {
        this.refreshRemote = refreshRemote;
    }

    public String getRuleProfile() { return ruleProfile; }

    public void setRuleProfile(String ruleProfile) { this.ruleProfile = ruleProfile; }
}
