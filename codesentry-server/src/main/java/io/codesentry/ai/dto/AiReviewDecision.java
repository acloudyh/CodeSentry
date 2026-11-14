package io.codesentry.ai.dto;

import java.util.ArrayList;
import java.util.List;

public class AiReviewDecision {
  private String verdict;
  private double confidence;
  private String reason;
  private List<Integer> evidenceLines = new ArrayList<>();
  private List<String> missingEvidence = new ArrayList<>();
  private String recommendation;

  public String getVerdict() {
    return verdict;
  }

  public void setVerdict(String verdict) {
    this.verdict = verdict;
  }

  public double getConfidence() {
    return confidence;
  }

  public void setConfidence(double confidence) {
    this.confidence = confidence;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public List<Integer> getEvidenceLines() {
    return evidenceLines;
  }

  public void setEvidenceLines(List<Integer> evidenceLines) {
    this.evidenceLines = evidenceLines;
  }

  public List<String> getMissingEvidence() {
    return missingEvidence;
  }

  public void setMissingEvidence(List<String> missingEvidence) {
    this.missingEvidence = missingEvidence;
  }

  public String getRecommendation() {
    return recommendation;
  }

  public void setRecommendation(String recommendation) {
    this.recommendation = recommendation;
  }
}
