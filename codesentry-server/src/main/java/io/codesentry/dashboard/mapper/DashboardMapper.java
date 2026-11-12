package io.codesentry.dashboard.mapper;

import io.codesentry.dashboard.dto.DashboardSummary;
import org.apache.ibatis.annotations.Select;

public interface DashboardMapper {
    // 独立子查询避免项目、资产、扫描一对多关联放大数量；不受最近记录的分页上限影响。
    // 新增问题按每轮历史累计，包含跨轮次再次新增的问题，不代表当前待整改存量。
    @Select("SELECT (SELECT COUNT(*) FROM business_project) business_projects, " +
            "(SELECT COUNT(*) FROM scan_project WHERE enabled=1) assets, " +
            "(SELECT COUNT(*) FROM scan_project WHERE enabled=1 AND source_type!='JAR_UPLOAD') code_assets, " +
            "(SELECT COUNT(*) FROM scan_project WHERE enabled=1 AND source_type='JAR_UPLOAD') jar_assets, " +
            "(SELECT COUNT(*) FROM scan_project p WHERE p.enabled=1 AND NOT EXISTS " +
            "(SELECT 1 FROM business_project_asset a WHERE a.asset_id=p.id)) unassigned_assets, " +
            "COUNT(*) scans, COALESCE(SUM(status IN ('PENDING','PREPARING','RUNNING')),0) running_scans, " +
            "COALESCE(SUM(new_findings),0) new_findings FROM scan_run")
    DashboardSummary summary();
}
