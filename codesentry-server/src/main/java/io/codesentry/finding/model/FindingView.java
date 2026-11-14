package io.codesentry.finding.model;

import java.time.LocalDateTime;

public class FindingView extends ScanFinding {
  /** 历史记录按当前规则分类展示，不改写原始扫描结果。 */
  @com.fasterxml.jackson.annotation.JsonProperty(
      access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
  public String getIssueType() {
    return io.codesentry.finding.service.FindingClassification.type(this);
  }

  @com.fasterxml.jackson.annotation.JsonProperty(
      access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
  public java.util.Map<String, String> getPrecheck() {
    return io.codesentry.finding.service.FindingClassification.precheck(this);
  }

  private OnlineRemediationEvidence onlineRemediation;

  public OnlineRemediationEvidence getOnlineRemediation() {
    return onlineRemediation;
  }

  public void setOnlineRemediation(OnlineRemediationEvidence value) {
    onlineRemediation = value;
  }

  private Long instanceId;
  private Long scanRunId;
  private String filePath;
  private Integer startLine;
  private Integer endLine;
  private String codeSnippet;
  private String resultStatus;
  private String triageStatus;
  private String triageComment;
  private String reviewer;
  private LocalDateTime expiresAt;

  public Long getInstanceId() {
    return instanceId;
  }

  public void setInstanceId(Long instanceId) {
    this.instanceId = instanceId;
  }

  public Long getScanRunId() {
    return scanRunId;
  }

  public void setScanRunId(Long scanRunId) {
    this.scanRunId = scanRunId;
  }

  public String getFilePath() {
    return filePath;
  }

  public void setFilePath(String filePath) {
    this.filePath = filePath;
  }

  public Integer getStartLine() {
    return startLine;
  }

  public void setStartLine(Integer startLine) {
    this.startLine = startLine;
  }

  public Integer getEndLine() {
    return endLine;
  }

  public void setEndLine(Integer endLine) {
    this.endLine = endLine;
  }

  public String getCodeSnippet() {
    return codeSnippet;
  }

  public void setCodeSnippet(String codeSnippet) {
    this.codeSnippet = codeSnippet;
  }

  public String getResultStatus() {
    return resultStatus;
  }

  public void setResultStatus(String resultStatus) {
    this.resultStatus = resultStatus;
  }

  public String getTriageStatus() {
    return triageStatus;
  }

  public void setTriageStatus(String triageStatus) {
    this.triageStatus = triageStatus;
  }

  public String getTriageComment() {
    return triageComment;
  }

  public void setTriageComment(String triageComment) {
    this.triageComment = triageComment;
  }

  public String getReviewer() {
    return reviewer;
  }

  public void setReviewer(String reviewer) {
    this.reviewer = reviewer;
  }

  public LocalDateTime getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(LocalDateTime expiresAt) {
    this.expiresAt = expiresAt;
  }
}
