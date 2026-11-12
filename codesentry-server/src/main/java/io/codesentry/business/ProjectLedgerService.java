package io.codesentry.business;

import io.codesentry.finding.model.FindingView;
import io.codesentry.report.service.RemediationReportService;
import io.codesentry.ai.service.AiReviewService;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ProjectLedgerService {
    private final JdbcTemplate db;
    private final BusinessProjectService projects;
    private final RemediationReportService reports;
    private final AiReviewService ai;
    private final io.codesentry.finding.service.FindingPersistenceService evidence;
    public ProjectLedgerService(JdbcTemplate db,BusinessProjectService projects,RemediationReportService reports,AiReviewService ai,io.codesentry.finding.service.FindingPersistenceService evidence) { this.db=db;this.projects=projects;this.reports=reports;this.ai=ai;this.evidence=evidence; }
    public Map<String,Object> page(Long projectId,Long roundId,Long assetId,String severity,String triage,String reportState,String keyword,int page,int size) {
        projects.require(projectId);
        if(roundId==null) roundId=projects.latestRoundId(projectId);
        page=Math.max(1,page); size=Math.min(500,Math.max(1,size));
        if(roundId==null) return Map.of("items",List.of(),"total",0,"page",page,"pageSize",size);
        ProjectScanRound round=projects.round(projectId,roundId);
        List<LedgerFinding> result=new ArrayList<>();
        int scopeTotal=0,scopeNew=0,scopeFixed=0;
        for(ProjectScanItem item:round.items) {
            if(item.runId==null || !List.of("COMPLETED","PARTIAL").contains(item.status) || (assetId!=null && !assetId.equals(item.assetId))) continue;
            Set<Long> included=new HashSet<>(reports.includedFindingIds(item.runId));
            Set<Long> reviewed=ai.latestForRun(item.runId).stream().map(r->r.getFindingId()).collect(Collectors.toSet());
            List<LedgerFinding> rows=db.query("SELECT f.*, i.id instance_id,i.scan_run_id,i.file_path,i.start_line,i.end_line,i.code_snippet,i.result_status,t.triage_status,t.comment triage_comment,t.reviewer,t.expires_at FROM scan_finding_instance i JOIN scan_finding f ON f.id=i.finding_id LEFT JOIN scan_triage t ON t.finding_id=f.id AND t.project_id=f.project_id WHERE i.scan_run_id=? ORDER BY FIELD(f.severity,'CRITICAL','HIGH','MEDIUM','LOW','INFO'),f.id",new BeanPropertyRowMapper<>(LedgerFinding.class),item.runId);
            for(LedgerFinding f:rows) {
                f.assetName=item.assetName; f.assetId=item.assetId;
                f.reportState=included.contains(f.getId())?"INCLUDED":f.getTriageStatus()!=null || reviewed.contains(f.getId())?"EXCLUDED":"PENDING";
                scopeTotal++;
                if ("NEW".equals(f.getResultStatus()) || "REOPENED".equals(f.getResultStatus())) scopeNew++;
                if ("FIXED".equals(f.getResultStatus())) scopeFixed++;
                if(!all(severity) && !severity.equals(f.getSeverity())) continue;
                if(!all(triage) && !(triage.equals("PENDING")?f.getTriageStatus()==null:triage.equals(f.getTriageStatus()))) continue;
                if(!all(reportState) && !reportState.equals(f.reportState)) continue;
                if(keyword!=null && !keyword.isBlank() && !(f.getTitle()+" "+f.getRuleId()+" "+f.getFilePath()+" "+f.assetName).toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) continue;
                result.add(f);
            }
        }
        result.sort(Comparator.comparingInt((LedgerFinding f)->List.of("CRITICAL","HIGH","MEDIUM","LOW","INFO").indexOf(f.getSeverity())).thenComparing(LedgerFinding::getId));
        int pendingReviewCount=(int)result.stream().filter(f->"PENDING".equals(f.reportState)).count();
        int includedCount=(int)result.stream().filter(f->"INCLUDED".equals(f.reportState)).count();
        int from=(int)Math.min(result.size(),(long)(page-1)*size);
        List<LedgerFinding> pageRows = result.subList(from,Math.min(result.size(),from+size));
        pageRows.forEach(evidence::enrichSnippet);
        Map<String,Object> response = new LinkedHashMap<>(Map.of("items",pageRows,"total",result.size(),"page",page,"pageSize",size,"roundId",roundId,"pendingReviewCount",pendingReviewCount,"includedCount",includedCount));
        response.put("summary",Map.of("total",scopeTotal,"newCount",scopeNew,"fixedCount",scopeFixed));
        return response;
    }
    private boolean all(String s) { return s==null || s.isEmpty() || "ALL".equals(s); }
    public static class LedgerFinding extends FindingView {
        public Long assetId; public String assetName; public String reportState;
    }
}
