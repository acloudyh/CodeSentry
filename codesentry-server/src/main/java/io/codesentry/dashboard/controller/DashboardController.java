package io.codesentry.dashboard.controller;

import io.codesentry.common.api.ApiResponse;
import io.codesentry.dashboard.dto.DashboardSummary;
import io.codesentry.dashboard.mapper.DashboardMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供业务项目、扫描资产及完整历史扫描的仪表盘统计入口。 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
    private final DashboardMapper dashboard;
    /** 注入全量统计查询，避免从分页列表推导累计指标。 */
    public DashboardController(DashboardMapper dashboard) { this.dashboard = dashboard; }
    /** 读取项目、资产分类和历史累计数，不受最近记录条数限制。 */
    @GetMapping("/summary")
    public ApiResponse<DashboardSummary> summary() { return ApiResponse.success(dashboard.summary()); }
}
