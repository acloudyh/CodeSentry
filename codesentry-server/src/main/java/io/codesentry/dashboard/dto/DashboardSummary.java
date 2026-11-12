package io.codesentry.dashboard.dto;

public class DashboardSummary {
    private long businessProjects;
    private long assets;
    private long codeAssets;
    private long jarAssets;
    private long unassignedAssets;
    private long scans;
    private long runningScans;
    private long newFindings;

    public long getBusinessProjects() { return businessProjects; }
    public long getAssets() { return assets; }
    public long getCodeAssets() { return codeAssets; }
    public long getJarAssets() { return jarAssets; }
    public long getUnassignedAssets() { return unassignedAssets; }
    public long getScans() { return scans; }
    public long getRunningScans() { return runningScans; }
    public long getNewFindings() { return newFindings; }
    // 旧客户端的 projects 表示扫描资产，保留别名，不改成业务项目数。
    public long getProjects() { return assets; }
}
