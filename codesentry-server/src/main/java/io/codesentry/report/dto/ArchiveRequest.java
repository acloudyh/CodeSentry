package io.codesentry.report.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;

/** 显式归档请求：单资产扫描与业务项目轮次二选一，拒绝模糊的混合来源。 */
public class ArchiveRequest {
  @Positive public Long scanRunId;
  @Positive public Long businessProjectId;
  @Positive public Long roundId;

  @AssertTrue(message = "请选择来源扫描，或者同时提供业务项目与项目轮次")
  public boolean isValidSource() {
    return scanRunId != null
        ? businessProjectId == null && roundId == null
        : businessProjectId != null && roundId != null;
  }

  public String source() {
    return scanRunId != null ? "scan:" + scanRunId : "project:" + businessProjectId + ":" + roundId;
  }
}
