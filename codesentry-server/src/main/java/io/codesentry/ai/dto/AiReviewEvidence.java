package io.codesentry.ai.dto;

public class AiReviewEvidence {
  private Long findingId;
  private Long scanRunId;
  private String scanner;
  private String ruleId;
  private String category;
  private String severity;
  private String title;
  private String description;
  private String recommendation;
  private String cwe;
  private String filePath;
  private Integer startLine;
  private Integer endLine;
  private String codeSnippet;

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

  public String getScanner() {
    return scanner;
  }

  public void setScanner(String scanner) {
    this.scanner = scanner;
  }

  public String getRuleId() {
    return ruleId;
  }

  public void setRuleId(String ruleId) {
    this.ruleId = ruleId;
  }

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public String getSeverity() {
    return severity;
  }

  public void setSeverity(String severity) {
    this.severity = severity;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getRecommendation() {
    return recommendation;
  }

  public void setRecommendation(String recommendation) {
    this.recommendation = recommendation;
  }

  public String getCwe() {
    return cwe;
  }

  public void setCwe(String cwe) {
    this.cwe = cwe;
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
}
