package io.codesentry.report.controller;

import io.codesentry.common.api.ApiResponse;
import io.codesentry.report.service.ReportAcceptanceService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reports/scan-runs/{runId}/acceptance")
public class ReportAcceptanceController {
  private final ReportAcceptanceService service;

  public ReportAcceptanceController(ReportAcceptanceService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<Map<String, Object>> detail(
      @PathVariable Long runId, @RequestParam(required = false) Long verificationId) {
    return ApiResponse.success(service.detail(runId, verificationId));
  }
}
