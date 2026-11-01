package io.codesentry.report.dto;

import io.codesentry.scan.model.ScanMode;
import io.codesentry.scan.model.ScanRunStatus;
import java.time.LocalDateTime;

public class RemediationReportSummary {
    private final Long id;
    private final Long projectId;
    private final ScanMode scanMode;
    private final ScanRunStatus status;
    private final int totalFindings;
    private final int newFindings;
    private final LocalDateTime createdAt;

    public RemediationReportSummary(Long id, Long projectId, ScanMode scanMode, ScanRunStatus status,
                                    int totalFindings, int newFindings, LocalDateTime createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.scanMode = scanMode;
        this.status = status;
        this.totalFindings = totalFindings;
        this.newFindings = newFindings;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public ScanMode getScanMode() { return scanMode; }
    public ScanRunStatus getStatus() { return status; }
    public int getTotalFindings() { return totalFindings; }
    public int getNewFindings() { return newFindings; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
