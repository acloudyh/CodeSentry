package io.codesentry.report.dto;

import io.codesentry.scan.model.ScanMode;
import io.codesentry.scan.model.ScanRunStatus;

public class RemediationReportSummary {
    private final Long id;
    private final Long projectId;
    private final ScanMode scanMode;
    private final ScanRunStatus status;
    private final int totalFindings;
    private final int newFindings;

    public RemediationReportSummary(Long id, Long projectId, ScanMode scanMode, ScanRunStatus status,
                                    int totalFindings, int newFindings) {
        this.id = id;
        this.projectId = projectId;
        this.scanMode = scanMode;
        this.status = status;
        this.totalFindings = totalFindings;
        this.newFindings = newFindings;
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public ScanMode getScanMode() { return scanMode; }
    public ScanRunStatus getStatus() { return status; }
    public int getTotalFindings() { return totalFindings; }
    public int getNewFindings() { return newFindings; }
}
