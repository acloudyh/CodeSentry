package io.codesentry.ai.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class AiReviewRecord {
    private Long id;
    private Long projectId;
    private Long scanRunId;
    private Long findingId;
    private Long reviewJobId;
    private String reviewRound;
    private String provider;
    private String model;
    private String verdict;
    private BigDecimal confidence;
    private String reason;
    private String evidenceLines;
    private String missingEvidence;
    private String recommendation;
    private String inputHash;
    private String promptVersion;
    private String status;
    private String errorMessage;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getScanRunId() { return scanRunId; }
    public void setScanRunId(Long scanRunId) { this.scanRunId = scanRunId; }
    public Long getFindingId() { return findingId; }
    public void setFindingId(Long findingId) { this.findingId = findingId; }
    public Long getReviewJobId() { return reviewJobId; }
    public void setReviewJobId(Long reviewJobId) { this.reviewJobId = reviewJobId; }
    public String getReviewRound() { return reviewRound; }
    public void setReviewRound(String reviewRound) { this.reviewRound = reviewRound; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getEvidenceLines() { return evidenceLines; }
    public void setEvidenceLines(String evidenceLines) { this.evidenceLines = evidenceLines; }
    public String getMissingEvidence() { return missingEvidence; }
    public void setMissingEvidence(String missingEvidence) { this.missingEvidence = missingEvidence; }
    public String getRecommendation() { return recommendation; }
    public void setRecommendation(String recommendation) { this.recommendation = recommendation; }
    public String getInputHash() { return inputHash; }
    public void setInputHash(String inputHash) { this.inputHash = inputHash; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
