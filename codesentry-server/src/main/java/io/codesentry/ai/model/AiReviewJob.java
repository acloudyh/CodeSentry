package io.codesentry.ai.model;

import java.time.LocalDateTime;

public class AiReviewJob {
    private int concurrency = 1;
    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int value) { concurrency = value; }
    private Long id;
    private Long scanRunId;
    private String provider;
    private String model;
    private String status;
    private int totalFindings;
    private int completedFindings;
    private int failedFindings;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getScanRunId() { return scanRunId; }
    public void setScanRunId(Long scanRunId) { this.scanRunId = scanRunId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getTotalFindings() { return totalFindings; }
    public void setTotalFindings(int totalFindings) { this.totalFindings = totalFindings; }
    public int getCompletedFindings() { return completedFindings; }
    public void setCompletedFindings(int completedFindings) { this.completedFindings = completedFindings; }
    public int getFailedFindings() { return failedFindings; }
    public void setFailedFindings(int failedFindings) { this.failedFindings = failedFindings; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
