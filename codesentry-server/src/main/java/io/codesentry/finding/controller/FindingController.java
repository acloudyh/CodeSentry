package io.codesentry.finding.controller;

import io.codesentry.common.api.ApiResponse;
import io.codesentry.finding.dto.BatchTriageFindingRequest;
import io.codesentry.finding.dto.BatchTriageResult;
import io.codesentry.finding.dto.TriageFindingRequest;
import io.codesentry.finding.model.FindingView;
import io.codesentry.finding.service.FindingPersistenceService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/findings")
public class FindingController {

  @org.springframework.beans.factory.annotation.Autowired
  private io.codesentry.finding.service.QualityChecklistService quality;

  @GetMapping("/scan-runs/{runId}/quality.csv")
  public org.springframework.http.ResponseEntity<byte[]> quality(@PathVariable Long runId) {
    return org.springframework.http.ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=quality-checklist.csv")
        .contentType(org.springframework.http.MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .body(quality.asset(runId));
  }

  @org.springframework.beans.factory.annotation.Autowired
  private io.codesentry.finding.service.FindingRuleCatalog catalog;

  public static class RuleClassification {
    public String ruleId, issueType, reason;
  }

  @GetMapping("/rule-classifications")
  public ApiResponse<java.util.List<java.util.Map<String, Object>>> classifications() {
    return ApiResponse.success(catalog.list());
  }

  @PutMapping("/rule-classifications")
  public ApiResponse<Void> classification(@RequestBody RuleClassification body) {
    catalog.save(body.ruleId, body.issueType, body.reason);
    return ApiResponse.success(null);
  }

  private final FindingPersistenceService service;

  public FindingController(FindingPersistenceService service) {
    this.service = service;
  }

  public ApiResponse<List<FindingView>> recent(int limit) {
    return recent(limit, "ALL");
  }

  @GetMapping
  public ApiResponse<List<FindingView>> recent(
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "ALL") String issueType) {
    return ApiResponse.success(
        "ALL".equals(issueType)
            ? service.findRecent(limit)
            : service.findClassifiedRecent(limit, issueType));
  }

  @GetMapping("/scan-runs/{scanRunId}")
  public ApiResponse<List<FindingView>> byScanRun(@PathVariable Long scanRunId) {
    return ApiResponse.success(service.findByRunId(scanRunId));
  }

  @PutMapping("/{findingId}/triage")
  public ApiResponse<FindingView> triage(
      @PathVariable Long findingId, @Valid @RequestBody TriageFindingRequest request) {
    return ApiResponse.success(service.triage(findingId, request));
  }

  @PostMapping("/scan-runs/{scanRunId}/triage/batch")
  public ApiResponse<BatchTriageResult> batchTriage(
      @PathVariable Long scanRunId, @Valid @RequestBody BatchTriageFindingRequest request) {
    return ApiResponse.success(service.batchTriage(scanRunId, request));
  }
}
