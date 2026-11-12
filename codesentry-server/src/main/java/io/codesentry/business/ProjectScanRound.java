package io.codesentry.business;

public class ProjectScanRound {
    public boolean assetHistory;
    public boolean getAssetHistory() { return assetHistory; }
    public void setAssetHistory(boolean value) { assetHistory=value; }
    @com.fasterxml.jackson.annotation.JsonIgnore public String unselectedJson;
    public String getUnselectedJson() { return unselectedJson; }
    public void setUnselectedJson(String value) { unselectedJson = value; }
    public java.util.List<java.util.Map<String,Object>> unselectedAssets = new java.util.ArrayList<>();
    public Long id;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long businessProjectId;
    public Long getBusinessProjectId() { return businessProjectId; }
    public void setBusinessProjectId(Long value) { businessProjectId = value; }
    public String projectName;
    public String getProjectName() { return projectName; }
    public void setProjectName(String value) { projectName = value; }
    public String requestKey;
    public String getRequestKey() { return requestKey; }
    public void setRequestKey(String value) { requestKey = value; }
    public String requestHash;
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String value) { requestHash = value; }
    public boolean refreshRemote;
    public boolean getRefreshRemote() { return refreshRemote; }
    public void setRefreshRemote(boolean value) { refreshRemote = value; }
    public boolean fullScope;
    public boolean getFullScope() { return fullScope; }
    public void setFullScope(boolean value) { fullScope = value; }
    public int assetCount;
    public int getAssetCount() { return assetCount; }
    public void setAssetCount(int value) { assetCount = value; }
    public String networkMode;
    public String getNetworkMode() { return networkMode; }
    public void setNetworkMode(String value) { networkMode = value; }
    public String ruleProfile;
    public String getRuleProfile() { return ruleProfile; }
    public void setRuleProfile(String value) { ruleProfile = value; }
    public Long retryOf;
    public Long getRetryOf() { return retryOf; }
    public void setRetryOf(Long value) { retryOf = value; }
    public java.time.LocalDateTime createdAt;
    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime value) { createdAt = value; }
    public String status;
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public int completedCount;
    public int getCompletedCount() { return completedCount; }
    public void setCompletedCount(int value) { completedCount = value; }
    public int failedCount;
    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int value) { failedCount = value; }
    public java.util.List<ProjectScanItem> items;
    public java.util.List<ProjectScanItem> getItems() { return items; }
    public void setItems(java.util.List<ProjectScanItem> value) { items = value; }
}
