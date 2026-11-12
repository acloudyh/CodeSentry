package io.codesentry.dashboard.controller;

import io.codesentry.common.api.ApiResponse;
import io.codesentry.dashboard.dto.DashboardSummary;
import io.codesentry.dashboard.mapper.DashboardMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
    private final DashboardMapper dashboard;
    public DashboardController(DashboardMapper dashboard) { this.dashboard = dashboard; }
    @GetMapping("/summary")
    public ApiResponse<DashboardSummary> summary() { return ApiResponse.success(dashboard.summary()); }
}
