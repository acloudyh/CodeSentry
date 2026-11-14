package io.codesentry.ai.dto;

import io.codesentry.ai.model.AiReviewRecord;

public class AiReviewSummary {
  private Long findingId;
  private Long scanRunId;
  private AiReviewRecord primaryReview;
  private AiReviewRecord verificationReview;
  private String consolidatedVerdict;
  private double confidence;
  private boolean confidenceAvailable;
  private boolean disagreement;
  private boolean highConfidence;
  private String status;
  private String errorMessage;

  public Long getFindingId() {
    return findingId;
  }

  public void setFindingId(Long findingId) {
    this.findingId = findingId;
  }

  public Long getScanRunId() {
    return scanRunId;
  }

  public void setScanRunId(Long scanRunId) {
    this.scanRunId = scanRunId;
  }

  public AiReviewRecord getPrimaryReview() {
    return primaryReview;
  }

  public void setPrimaryReview(AiReviewRecord primaryReview) {
    this.primaryReview = primaryReview;
  }

  public AiReviewRecord getVerificationReview() {
    return verificationReview;
  }

  public void setVerificationReview(AiReviewRecord verificationReview) {
    this.verificationReview = verificationReview;
  }

  public String getConsolidatedVerdict() {
    return consolidatedVerdict;
  }

  public void setConsolidatedVerdict(String consolidatedVerdict) {
    this.consolidatedVerdict = consolidatedVerdict;
  }

  public double getConfidence() {
    return confidence;
  }

  public void setConfidence(double confidence) {
    this.confidence = confidence;
  }

  public boolean isConfidenceAvailable() {
    return confidenceAvailable;
  }

  public void setConfidenceAvailable(boolean confidenceAvailable) {
    this.confidenceAvailable = confidenceAvailable;
  }

  public boolean isDisagreement() {
    return disagreement;
  }

  public void setDisagreement(boolean disagreement) {
    this.disagreement = disagreement;
  }

  public boolean isHighConfidence() {
    return highConfidence;
  }

  public void setHighConfidence(boolean highConfidence) {
    this.highConfidence = highConfidence;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }
}
