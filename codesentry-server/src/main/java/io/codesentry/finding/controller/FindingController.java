package io.codesentry.finding.controller;

import io.codesentry.common.api.ApiResponse;
import io.codesentry.finding.model.FindingView;
import io.codesentry.finding.dto.TriageFindingRequest;
import io.codesentry.finding.dto.BatchTriageFindingRequest;
import io.codesentry.finding.dto.BatchTriageResult;
import io.codesentry.finding.service.FindingPersistenceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;

import javax.validation.Valid;

import java.util.List;

@RestController
@RequestMapping("/api/v1/findings")
public class FindingController {

    private final FindingPersistenceService service;

    public FindingController(FindingPersistenceService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<FindingView>> recent(@RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(service.findVisibleRecent(limit));
    }

    @GetMapping("/scan-runs/{scanRunId}")
    public ApiResponse<List<FindingView>> byScanRun(@PathVariable Long scanRunId) {
        return ApiResponse.success(service.findVisibleByRunId(scanRunId));
    }

    @PutMapping("/{findingId}/triage")
    public ApiResponse<FindingView> triage(@PathVariable Long findingId,
                                           @Valid @RequestBody TriageFindingRequest request) {
        return ApiResponse.success(service.triage(findingId, request));
    }

    @PostMapping("/scan-runs/{scanRunId}/triage/batch")
    public ApiResponse<BatchTriageResult> batchTriage(@PathVariable Long scanRunId,
                                                       @Valid @RequestBody BatchTriageFindingRequest request) {
        return ApiResponse.success(service.batchTriage(scanRunId, request));
    }
}
