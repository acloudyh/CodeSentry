package io.codesentry.business;

import io.codesentry.scan.model.NetworkMode;
import io.codesentry.scan.model.WorkspaceMode;
import javax.validation.Valid;
import javax.validation.constraints.*;
import java.util.List;

public class ProjectScanRequest {
    @NotBlank @Size(max=80) public String requestKey;
    @NotNull public NetworkMode networkMode;
    @NotBlank @Size(max=64) public String ruleProfile = "COMMUNITY_GENERAL";
    public boolean fullScope;
    public boolean refreshRemote;
    public boolean getRefreshRemote() { return refreshRemote; }
    public void setRefreshRemote(boolean value) { refreshRemote = value; }
    @NotEmpty @Valid public List<@NotNull Target> assets;
    public String getRequestKey() { return requestKey; }
    public void setRequestKey(String value) { requestKey=value; }
    public NetworkMode getNetworkMode() { return networkMode; }
    public void setNetworkMode(NetworkMode value) { networkMode=value; }
    public String getRuleProfile() { return ruleProfile; }
    public void setRuleProfile(String value) { ruleProfile=value; }
    public boolean getFullScope() { return fullScope; }
    public void setFullScope(boolean value) { fullScope=value; }
    public List<Target> getAssets() { return assets; }
    public void setAssets(List<Target> value) { assets=value; }
    public static class Target {
        @NotNull public Long assetId;
        @NotNull public WorkspaceMode workspaceMode;
        @Size(max=255) public String scanRef;
        public Long getAssetId() { return assetId; }
        public void setAssetId(Long value) { assetId=value; }
        public WorkspaceMode getWorkspaceMode() { return workspaceMode; }
        public void setWorkspaceMode(WorkspaceMode value) { workspaceMode=value; }
        public String getScanRef() { return scanRef; }
        public void setScanRef(String value) { scanRef=value; }
    }
}
