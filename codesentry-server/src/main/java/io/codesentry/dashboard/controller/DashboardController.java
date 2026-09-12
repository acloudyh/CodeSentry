package io.codesentry.dashboard.controller;

import io.codesentry.common.api.ApiResponse;
import io.codesentry.dashboard.dto.DashboardSummary;
import io.codesentry.project.service.ScanProjectService;
import io.codesentry.scan.model.ScanRun;
import io.codesentry.scan.model.ScanRunStatus;
import io.codesentry.scan.service.ScanRunService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final ScanProjectService projectService;
    private final ScanRunService scanRunService;

    public DashboardController(ScanProjectService projectService, ScanRunService scanRunService) {
        this.projectService = projectService;
        this.scanRunService = scanRunService;
    }

    @GetMapping("/summary")
    public ApiResponse<DashboardSummary> summary() {
        List<ScanRun> runs = scanRunService.recent(200);
        int running = (int) runs.stream().filter(run -> run.getStatus() == ScanRunStatus.PENDING
                || run.getStatus() == ScanRunStatus.PREPARING
                || run.getStatus() == ScanRunStatus.RUNNING).count();
        int newFindings = runs.stream()
                .filter(run -> run.getNewFindings() != null)
                .mapToInt(ScanRun::getNewFindings)
                .sum();
        return ApiResponse.success(new DashboardSummary(projectService.findAll().size(), runs.size(), running, newFindings));
    }
}

