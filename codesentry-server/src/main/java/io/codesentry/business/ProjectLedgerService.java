package io.codesentry.business;

import io.codesentry.finding.model.FindingView;
import io.codesentry.report.service.RemediationReportService;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

/** 聚合指定项目扫描范围的问题台账，复用原问题身份、人工结论与报告采纳口径。 */
@Service
public class ProjectLedgerService {
    private final JdbcTemplate db;
    private final BusinessProjectService projects;
    private final RemediationReportService reports;
    private final io.codesentry.finding.service.FindingPersistenceService evidence;
    /** 注入项目范围、原报告采纳、工具复核与扫描证据服务。 */
    public ProjectLedgerService(JdbcTemplate db,BusinessProjectService projects,RemediationReportService reports,io.codesentry.finding.service.FindingPersistenceService evidence) { this.db=db;this.projects=projects;this.reports=reports;this.evidence=evidence; }
    /** 按轮次和资产读取原扫描问题，在数据库计算全范围统计并筛选分页；失败项不借用历史结果。 */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Map<String,Object> page(Long projectId,Long roundId,Long assetId,String severity,String triage,String reportState,String keyword,int page,int size) {
        projects.require(projectId);
        if(roundId==null) roundId=projects.latestRoundId(projectId);
        page=Math.max(1,page); size=Math.min(500,Math.max(1,size));
        if(roundId==null) return Map.of("items",List.of(),"total",0,"page",page,"pageSize",size);
        ProjectScanRound round=projects.round(projectId,roundId);
        List<ProjectScanItem> scope = round.items.stream().filter(item -> item.runId != null
                && List.of("COMPLETED", "PARTIAL").contains(item.status)
                && (assetId == null || assetId.equals(item.assetId))).collect(Collectors.toList());
        if (scope.isEmpty()) return Map.of("items",List.of(),"total",0,"page",page,"pageSize",size,
                "roundId",roundId,"pendingReviewCount",0,"includedCount",0,
                "summary",Map.of("total",0,"newCount",0,"fixedCount",0));

        List<Object> scopeArgs = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        List<String> adopted = new ArrayList<>();
        List<Object> adoptionArgs = new ArrayList<>();
        // 轮次范围来自服务端，失败资产不补旧结果；采纳身份按来源扫描区分，保持归档口径。
        for (ProjectScanItem item : scope) {
            sources.add("SELECT ? AS run_id, ? AS asset_id, ? AS asset_name");
            Collections.addAll(scopeArgs, item.runId, item.assetId, item.assetName);
            List<Long> ids = reports.includedFindingIds(item.runId);
            if (!ids.isEmpty()) {
                adopted.add("(i.scan_run_id=? AND f.id IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + "))");
                adoptionArgs.add(item.runId); adoptionArgs.addAll(ids);
            }
        }
        String joins = " FROM (" + String.join(" UNION ALL ", sources) + ") scope "
                + "JOIN scan_finding_instance i ON i.scan_run_id=scope.run_id "
                + "JOIN scan_finding f ON f.id=i.finding_id "
                + "LEFT JOIN scan_triage t ON t.finding_id=f.id AND t.project_id=f.project_id";
        Map<String,Object> counts = db.queryForMap("SELECT COUNT(*) total, "
                + "COALESCE(SUM(i.result_status IN ('NEW','REOPENED')),0) newCount, "
                + "COALESCE(SUM(i.result_status='FIXED'),0) fixedCount" + joins, scopeArgs.toArray());
        String included = adopted.isEmpty() ? "FALSE" : "(" + String.join(" OR ", adopted) + ")";
        String state = "CASE WHEN " + included + " THEN 'INCLUDED' "
                + "WHEN t.triage_status IS NOT NULL OR ar.finding_id IS NOT NULL THEN 'EXCLUDED' ELSE 'PENDING' END";
        String reviews = " LEFT JOIN (SELECT DISTINCT scan_run_id,finding_id FROM scan_ai_review WHERE scan_run_id IN ("
                + String.join(",", Collections.nCopies(scope.size(), "?"))
                + ")) ar ON ar.scan_run_id=i.scan_run_id AND ar.finding_id=f.id";
        String base = "SELECT i.id instance_id,f.id,f.severity,f.title,f.rule_id,i.file_path,t.triage_status,"
                + "scope.asset_id,scope.asset_name," + state + " report_state" + joins + reviews;
        List<Object> args = new ArrayList<>(adoptionArgs); args.addAll(scopeArgs);
        scope.forEach(item -> args.add(item.runId));
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (!all(severity)) { where.append(" AND q.severity=?"); args.add(severity); }
        if (!all(triage)) {
            if ("PENDING".equals(triage)) where.append(" AND q.triage_status IS NULL");
            else { where.append(" AND q.triage_status=?"); args.add(triage); }
        }
        if (!all(reportState)) { where.append(" AND q.report_state=?"); args.add(reportState); }
        if (keyword != null && !keyword.isBlank()) {
            // LOCATE treats percent/underscore as literal search text, consistent with the previous contains search.
            where.append(" AND LOCATE(?,LOWER(CONCAT(COALESCE(q.title,'null'),' ',COALESCE(q.rule_id,'null'),"
                    + "' ',COALESCE(q.file_path,'null'),' ',COALESCE(q.asset_name,'null'))))>0");
            args.add(keyword.toLowerCase(Locale.ROOT));
        }
        String filtered = " FROM (" + base + ") q" + where;
        Map<String,Object> totals = db.queryForMap("SELECT COUNT(*) total, "
                + "COALESCE(SUM(q.report_state='PENDING'),0) pending, "
                + "COALESCE(SUM(q.report_state='INCLUDED'),0) included" + filtered, args.toArray());
        // 先在数据库完成排序分页，再 JOIN 大字段；源码回读严格限制在当前页。
        String window = "SELECT q.instance_id,q.asset_id,q.asset_name,q.report_state" + filtered
                + " ORDER BY FIELD(q.severity,'CRITICAL','HIGH','MEDIUM','LOW','INFO'),q.id,q.instance_id LIMIT ? OFFSET ?";
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add((long)(page-1)*size);
        List<LedgerFinding> pageRows = db.query("SELECT f.*,i.id instance_id,i.scan_run_id,i.file_path,i.start_line,i.end_line,"
                + "i.code_snippet,i.result_status,t.triage_status,t.comment triage_comment,t.reviewer,t.expires_at,"
                + "p.asset_id,p.asset_name,p.report_state FROM (" + window + ") p "
                + "JOIN scan_finding_instance i ON i.id=p.instance_id JOIN scan_finding f ON f.id=i.finding_id "
                + "LEFT JOIN scan_triage t ON t.finding_id=f.id AND t.project_id=f.project_id "
                + "ORDER BY FIELD(f.severity,'CRITICAL','HIGH','MEDIUM','LOW','INFO'),f.id,i.id",
                new BeanPropertyRowMapper<>(LedgerFinding.class), pageArgs.toArray());
        pageRows.forEach(evidence::enrichSnippet);
        Map<String,Object> response = new LinkedHashMap<>(Map.of("items",pageRows,"total",number(totals,"total"),
                "page",page,"pageSize",size,"roundId",roundId,"pendingReviewCount",number(totals,"pending"),
                "includedCount",number(totals,"included")));
        response.put("summary",Map.of("total",number(counts,"total"),"newCount",number(counts,"newCount"),"fixedCount",number(counts,"fixedCount")));
        return response;
    }
    /** JDBC 聚合数值统一为接口原有整数类型，空范围由 SQL COALESCE 转为零。 */
    private int number(Map<String,Object> row, String key) { return ((Number)row.get(key)).intValue(); }
    /** 判断筛选值是否表示全部，以统一处理空条件和 ALL。 */
    private boolean all(String s) { return s==null || s.isEmpty() || "ALL".equals(s); }
    public static class LedgerFinding extends FindingView {
        public Long assetId; public String assetName; public String reportState;
        public Long getAssetId() { return assetId; }
        public void setAssetId(Long assetId) { this.assetId = assetId; }
        public String getAssetName() { return assetName; }
        public void setAssetName(String assetName) { this.assetName = assetName; }
        public String getReportState() { return reportState; }
        public void setReportState(String reportState) { this.reportState = reportState; }
    }
}
