package io.codesentry.report.dto;

import io.codesentry.scan.model.ScanMode;
import io.codesentry.scan.model.ScanRunStatus;
import java.time.LocalDateTime;

/** 单份报告的列表摘要，区分原扫描状态、联网核实状态、台账条数和合并整改组数。 */
public class RemediationReportSummary {
    private io.codesentry.scan.model.NetworkMode networkMode;
    private String remediationStatus,remediationMessage;
    /** 本次原扫描是否选择联网模式，与当前核实进度分开表达。 */
    public io.codesentry.scan.model.NetworkMode getNetworkMode() { return networkMode; }
    /** 联网修复依据的独立处理状态，不覆盖原扫描执行状态。 */
    public String getRemediationStatus() { return remediationStatus; }
    /** 联网核实进度或未完成原因，供列表状态提示使用。 */
    public String getRemediationMessage() { return remediationMessage; }
    /** 关联原扫描的联网模式、处理状态和进度说明，不改变扫描执行状态。 */
    /** 本次原扫描是否选择联网模式，与当前核实进度分开表达。 */
    /** 联网修复依据的独立处理状态，不覆盖原扫描执行状态。 */
    /** 联网核实进度或未完成原因，供列表状态提示使用。 */
    public RemediationReportSummary withRemediation(io.codesentry.scan.model.ScanRun run) { networkMode=run.getNetworkMode();remediationStatus=run.getRemediationStatus();remediationMessage=run.getRemediationMessage();return this; }
    private final Long id;
    private final Long projectId;
    private final ScanMode scanMode;
    private final ScanRunStatus status;
    private final int totalFindings;
    private final int newFindings;
    private final LocalDateTime createdAt;
    private final int ledgerFindings;
    private final int detailGroups;
    private final int notIncludedFindings;

    public RemediationReportSummary(Long id, Long projectId, ScanMode scanMode, ScanRunStatus status,
                                    int totalFindings, int newFindings, LocalDateTime createdAt) {
        this(id, projectId, scanMode, status, totalFindings, newFindings, createdAt,
                totalFindings, totalFindings, 0);
    }

    public RemediationReportSummary(Long id, Long projectId, ScanMode scanMode, ScanRunStatus status,
                                    int totalFindings, int newFindings, LocalDateTime createdAt,
                                    int ledgerFindings, int detailGroups, int notIncludedFindings) {
        this.id = id;
        this.projectId = projectId;
        this.scanMode = scanMode;
        this.status = status;
        this.totalFindings = totalFindings;
        this.newFindings = newFindings;
        this.createdAt = createdAt;
        this.ledgerFindings = ledgerFindings;
        this.detailGroups = detailGroups;
        this.notIncludedFindings = notIncludedFindings;
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public ScanMode getScanMode() { return scanMode; }
    public ScanRunStatus getStatus() { return status; }
    /** 最终纳入整改报告的问题条数，依赖合并前计数。 */
    public int getTotalFindings() { return totalFindings; }
    public int getNewFindings() { return newFindings; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    /** 原扫描台账问题条数，包含最终未采纳的问题。 */
    public int getLedgerFindings() { return ledgerFindings; }
    /** 报告合并展示的整改组数，不用于替代原问题条数。 */
    public int getDetailGroups() { return detailGroups; }
    /** 台账中未纳入报告正文的问题条数，附录保留排除原因。 */
    public int getNotIncludedFindings() { return notIncludedFindings; }
}
