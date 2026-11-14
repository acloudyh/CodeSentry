package io.codesentry.finding.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public class TriageFindingRequest {

  @NotBlank(message = "复核结论不能为空")
  @Pattern(regexp = "CONFIRMED|FALSE_POSITIVE|ACCEPTED_RISK|FIXED_PENDING", message = "不支持的复核结论")
  private String triageStatus;

  @Size(max = 2000, message = "复核说明不能超过 2000 个字符")
  private String comment;

  @NotBlank(message = "复核人不能为空")
  @Size(max = 128, message = "复核人不能超过 128 个字符")
  private String reviewer;

  private LocalDateTime expiresAt;

  public String getTriageStatus() {
    return triageStatus;
  }

  public void setTriageStatus(String triageStatus) {
    this.triageStatus = triageStatus;
  }

  public String getComment() {
    return comment;
  }

  public void setComment(String comment) {
    this.comment = comment;
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
