package io.codesentry.report.dto;

import io.codesentry.scan.model.ScanMode;
import io.codesentry.scan.model.ScanRunStatus;
import java.time.LocalDateTime;

public class RemediationReportSummary {
    private io.codesentry.scan.model.NetworkMode networkMode;
    private String remediationStatus,remediationMessage;
    public io.codesentry.scan.model.NetworkMode getNetworkMode() { return networkMode; }
    public String getRemediationStatus() { return remediationStatus; }
    public String getRemediationMessage() { return remediationMessage; }
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
    public int getTotalFindings() { return totalFindings; }
    public int getNewFindings() { return newFindings; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public int getLedgerFindings() { return ledgerFindings; }
    public int getDetailGroups() { return detailGroups; }
    public int getNotIncludedFindings() { return notIncludedFindings; }
}
