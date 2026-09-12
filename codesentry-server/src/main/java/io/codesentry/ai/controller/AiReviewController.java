package io.codesentry.ai.controller;

import io.codesentry.ai.dto.AiReviewSummary;
import io.codesentry.ai.dto.AiReviewConfigurationView;
import io.codesentry.ai.model.AiReviewJob;
import io.codesentry.ai.model.AiReviewRecord;
import io.codesentry.ai.service.AiReviewService;
import io.codesentry.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ai-reviews")
public class AiReviewController {
    private final AiReviewService service;

    public AiReviewController(AiReviewService service) { this.service = service; }

    @GetMapping("/config")
    public ApiResponse<AiReviewConfigurationView> configuration() {
        return ApiResponse.success(service.configuration());
    }

    @PostMapping("/findings/{findingId}")
    public ApiResponse<AiReviewSummary> reviewFinding(@PathVariable Long findingId) {
        return ApiResponse.success(service.reviewFinding(findingId));
    }

    @GetMapping("/findings/{findingId}")
    public ApiResponse<AiReviewSummary> findingReview(@PathVariable Long findingId) {
        return ApiResponse.success(service.latestForFinding(findingId));
    }

    @PostMapping("/scan-runs/{scanRunId}")
    public ApiResponse<AiReviewJob> reviewRun(@PathVariable Long scanRunId) {
        return ApiResponse.success(service.startRunReview(scanRunId));
    }

    @GetMapping("/scan-runs/{scanRunId}")
    public ApiResponse<List<AiReviewSummary>> runReviews(@PathVariable Long scanRunId) {
        return ApiResponse.success(service.latestForRun(scanRunId));
    }

    @GetMapping("/scan-runs/{scanRunId}/jobs/latest")
    public ApiResponse<AiReviewJob> latestJob(@PathVariable Long scanRunId) {
        return ApiResponse.success(service.latestJobForRun(scanRunId));
    }

    @GetMapping("/jobs/{jobId}")
    public ApiResponse<AiReviewJob> job(@PathVariable Long jobId) {
        return ApiResponse.success(service.requireJob(jobId));
    }

    @GetMapping("/jobs/{jobId}/records")
    public ApiResponse<List<AiReviewRecord>> jobRecords(@PathVariable Long jobId) {
        return ApiResponse.success(service.recordsForJob(jobId));
    }
}
