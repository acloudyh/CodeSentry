package io.codesentry.dashboard.dto;

/** 仪表盘全量统计结果；业务项目与扫描资产分别计数，保留旧 projects 资产别名。 */
public class DashboardSummary {
    private long businessProjects;
    private long assets;
    private long codeAssets;
    private long jarAssets;
    private long unassignedAssets;
    private long scans;
    private long runningScans;
    private long newFindings;

    /** 业务项目总数，包含尚未登记资产或扫描的空项目。 */
    public long getBusinessProjects() { return businessProjects; }
    /** 当前有效资产数量，同时包含本地代码、Git 镜像和 JAR。 */
    public long getAssets() { return assets; }
    /** 当前有效的本地代码及远程 Git 资产数，不包含 JAR。 */
    public long getCodeAssets() { return codeAssets; }
    /** 当前有效的 JAR 上传资产数。 */
    public long getJarAssets() { return jarAssets; }
    /** 当前有效且未归属任何业务项目的资产数。 */
    public long getUnassignedAssets() { return unassignedAssets; }
    /** 所有状态的历史扫描批次数，不受最近列表分页上限影响。 */
    public long getScans() { return scans; }
    /** 排队、准备和执行中的扫描任务数，不包含独立审核或联网补查作业。 */
    public long getRunningScans() { return runningScans; }
    /** 各轮扫描新增问题数的历史累计，不代表当前待整改存量。 */
    public long getNewFindings() { return newFindings; }
    // 旧客户端的 projects 表示扫描资产，保留别名，不改成业务项目数。
    /** 兼容旧 API 的扫描资产数；业务项目数应读取 businessProjects。 */
    public long getProjects() { return assets; }
}
