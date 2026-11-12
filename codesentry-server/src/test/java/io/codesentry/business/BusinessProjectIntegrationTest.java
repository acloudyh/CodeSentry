package io.codesentry.business;

import io.codesentry.common.error.BusinessException;
import io.codesentry.project.mapper.ScanProjectMapper;
import io.codesentry.project.model.*;
import io.codesentry.scan.service.ScanOrchestrator;
import io.codesentry.scan.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.main.web-application-type=none", "logging.level.root=WARN", "codesentry.ai.enabled=false"})
@EnabledIfEnvironmentVariable(named="CODESENTRY_INTEGRATION_DB_URL",matches=".*codesentry_business_test_.*")
class BusinessProjectIntegrationTest {
    @DynamicPropertySource static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->System.getenv("CODESENTRY_INTEGRATION_DB_URL"));
        registry.add("codesentry.storage.root",()->System.getProperty("java.io.tmpdir")+"/codesentry-business-integration");
    }
    @MockBean ScanOrchestrator orchestrator;
    @Autowired BusinessProjectService service;
    @Autowired ProjectLedgerService ledger;
    @Autowired ProjectReportService reports;
    @Autowired JdbcTemplate db;
    @Autowired ScanProjectMapper assets;
    @Autowired io.codesentry.project.service.ScanProjectService assetService;
    @Autowired ObjectMapper json;
    @Autowired io.codesentry.scan.service.ScanRunService scanRecords;
    private Long asset(String type) {
        ScanProject a=new ScanProject();a.setProjectName(type+"-"+UUID.randomUUID());a.setSourceType(ProjectSourceType.valueOf(type));a.setLocalPath("/tmp/test.jar");a.setEnabled(true);a.setDefaultBranch("main");a.setCreatedAt(java.time.LocalDateTime.now());a.setUpdatedAt(a.getCreatedAt());assets.insert(a);return a.getId();
    }
    private ProjectScanRequest request(List<Long> ids,boolean full) {
        ProjectScanRequest r=new ProjectScanRequest();r.requestKey=UUID.randomUUID().toString();r.networkMode=NetworkMode.LOCAL;r.ruleProfile="LOCAL_OWASP";r.fullScope=full;r.assets=new ArrayList<>();
        for(Long id:ids) { ProjectScanRequest.Target t=new ProjectScanRequest.Target();t.assetId=id; t.workspaceMode=assets.findById(id).getSourceType()==ProjectSourceType.JAR_UPLOAD?WorkspaceMode.JAR_UPLOAD:WorkspaceMode.CURRENT;r.assets.add(t); }
        return r;
    }
    private void finish(ProjectScanRound r,String... statuses) { for(int i=0;i<statuses.length;i++) if(r.items.get(i).runId!=null) db.update("UPDATE scan_run SET status=?,finished_at=NOW(3) WHERE id=?",statuses[i],r.items.get(i).runId); }
    @Test void ownershipDeletionAndHistoricalAssetCompatibility() {
        BusinessProject p=service.save(null,"归属测试","");BusinessProject other=service.save(null,"另一个项目","");Long id=asset("LOCAL");
        assertNull(assets.findAll().stream().filter(a->a.getId().equals(id)).findFirst().orElseThrow().getBusinessProjectId());
        service.attach(p.id,id);service.attach(p.id,id);assertEquals(1,service.assets(p.id).size());
        assertThrows(BusinessException.class,()->service.attach(other.id,id));assertThrows(BusinessException.class,()->service.delete(p.id));
        service.detach(p.id,id);assertNotNull(assets.findById(id));service.attach(other.id,id);service.delete(p.id);
        assetService.delete(id);assertTrue(service.assets(other.id).isEmpty());assertFalse(assets.findById(id).getEnabled());service.delete(other.id);
    }
    @Test void mixedRoundIdempotencyScopeCancelRetryAndHistory() throws Exception {
        BusinessProject p=service.save(null,"混合项目","");Long code=asset("LOCAL"),jar=asset("JAR_UPLOAD");service.attach(p.id,code);service.attach(p.id,jar);
        ProjectScanRequest req=request(List.of(code,jar),true);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try { Future<ProjectScanRound> a=pool.submit(()->service.start(p.id,req));Future<ProjectScanRound> b=pool.submit(()->service.start(p.id,req));assertEquals(a.get().id,b.get().id); } finally {pool.shutdownNow();}
        ProjectScanRound r=service.start(p.id,req);assertEquals(2,r.items.size());assertTrue(r.fullScope);
        req.fullScope=false;assertThrows(BusinessException.class,()->service.start(p.id,req));
        assertThrows(BusinessException.class,()->service.start(p.id,request(List.of(code),true)));
        finish(r,"COMPLETED","FAILED");assertEquals("PARTIAL",service.round(p.id,r.id).status);
        ProjectScanRound retried=service.retry(p.id,r.id,"retry-"+r.id);assertEquals(1,retried.items.size());assertEquals(jar,retried.items.get(0).assetId);assertEquals(r.id,retried.retryOf);assertEquals(1,retried.unselectedAssets.size());
        service.cancel(p.id,retried.id);assertEquals("CANCELLED",service.round(p.id,retried.id).status);
        service.detach(p.id,jar);assertEquals(2,service.round(p.id,r.id).items.size());assertThrows(BusinessException.class,()->service.delete(p.id));
    }
    @Test void pureCodeAndPureJarProjectsKeepIndependentTargets() {
        for(String type:List.of("LOCAL","JAR_UPLOAD")) {
            BusinessProject p=service.save(null,type+"项目","");Long a=asset(type);service.attach(p.id,a);
            ProjectScanRound r=service.start(p.id,request(List.of(a),true));assertEquals(1,r.items.size());assertNotNull(r.items.get(0).runId);
            finish(r,"COMPLETED");assertEquals("COMPLETED",service.round(p.id,r.id).status);
            assertEquals(1,service.rounds(p.id,1,20).get("total") instanceof Number?((Number)service.rounds(p.id,1,20).get("total")).intValue():0);
        }
    }
    @Test void validationFailureRemainsVisibleAndNoOldResultsFillFailedAsset() {
        BusinessProject p=service.save(null,"失败范围","");Long jar=asset("JAR_UPLOAD");service.attach(p.id,jar);
        ProjectScanRequest req=request(List.of(jar),true);req.assets.get(0).workspaceMode=WorkspaceMode.CURRENT;
        ProjectScanRound r=service.start(p.id,req);assertEquals("FAILED",r.status);assertNull(r.items.get(0).runId);assertNotNull(r.items.get(0).errorMessage);
        assertEquals(0,ledger.page(p.id,r.id,null,null,null,null,null,1,20).get("total"));
    }
    private void findings(ProjectScanItem item,int count) {
        for(int n=0;n<count;n++) {
            String fp=String.format("%064d",n);
            db.update("INSERT INTO scan_finding(project_id,fingerprint,scanner,rule_id,severity,title,first_seen_at,last_seen_at) VALUES(?,?,'Semgrep','sample.rule','HIGH',?,NOW(3),NOW(3))",item.assetId,fp,"问题 "+n);
            Long finding=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
            db.update("INSERT INTO scan_finding_instance(finding_id,scan_run_id,file_path,result_status,created_at) VALUES(?,?,?,'NEW',NOW(3))",finding,item.runId,"src/Test"+n+".java");
            db.update("INSERT INTO scan_triage(project_id,finding_id,triage_status,reviewer,created_at,updated_at) VALUES(?,?,'CONFIRMED','测试复核人',NOW(3),NOW(3))",item.assetId,finding);
        }
    }
    @Test void projectLedgerRestoresSecretEvidenceFromHistoricalSnapshotWithoutRescan() throws Exception {
        BusinessProject p=service.save(null,"代码详情验证","");Long code=asset("LOCAL");service.attach(p.id,code);
        ProjectScanRound round=service.start(p.id,request(List.of(code),true));finish(round,"COMPLETED");
        ProjectScanItem item=round.items.get(0);findings(item,1);
        java.nio.file.Path source=java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"codesentry-business-integration","snapshots",String.valueOf(item.runId),"current","config.txt");
        java.nio.file.Files.createDirectories(source.getParent());java.nio.file.Files.writeString(source,"before\napi_key=fixture-only-value\nafter\n");
        db.update("UPDATE scan_finding SET scanner='Gitleaks',category='SECRET' WHERE project_id=?",code);
        db.update("UPDATE scan_finding_instance SET file_path=?,start_line=2,end_line=2,code_snippet='[REDACTED]' WHERE scan_run_id=?",source.toString(),item.runId);
        ProjectLedgerService.LedgerFinding finding=(ProjectLedgerService.LedgerFinding)((List<?>)ledger.page(p.id,round.id,null,null,null,null,null,1,20).get("items")).get(0);
        assertTrue(finding.getCodeSnippet().contains(">    2 | api_key=fixture-only-value"));assertTrue(finding.getCodeSnippet().contains("before"));assertEquals(item.runId,finding.getScanRunId());
        assertEquals("[REDACTED]",db.queryForObject("SELECT code_snippet FROM scan_finding_instance WHERE scan_run_id=?",String.class,item.runId));
        java.nio.file.Files.delete(source);
        finding=(ProjectLedgerService.LedgerFinding)((List<?>)ledger.page(p.id,round.id,null,null,null,null,null,1,20).get("items")).get(0);assertEquals("[REDACTED]",finding.getCodeSnippet());
    }
    @Test void standaloneHistoryIsVisibleAcrossProjectOverviewLedgerAndReportsWithoutCreatingRounds() throws Exception {
        BusinessProject p=service.save(null,"历史兼容","");BusinessProject other=service.save(null,"隔离项目","");Long code=asset("LOCAL");service.attach(p.id,code);
        Long latest=null;
        for(int n=0;n<205;n++) {
            db.update("INSERT INTO scan_run(project_id,scan_mode,workspace_mode,status,total_findings,new_findings,created_at,finished_at) VALUES(?,'INCREMENTAL','CURRENT',?,0,0,?,?)",code,n==0?"FAILED":"COMPLETED",java.time.LocalDateTime.of(2025,1,1,0,0).plusMinutes(n),java.time.LocalDateTime.of(2025,1,1,0,1).plusMinutes(n));
            latest=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        }
        ProjectScanItem original=new ProjectScanItem();original.assetId=code;original.runId=latest;findings(original,3);
        assertEquals(-latest,service.latestRoundId(p.id));
        ProjectScanRound h=service.round(p.id,-latest);assertTrue(h.assetHistory);assertEquals(latest,h.items.get(0).runId);assertEquals(ScanMode.INCREMENTAL,h.items.get(0).run.getScanMode());assertEquals("COMPLETED",h.status);
        assertEquals(205L,service.rounds(p.id,1,20).get("total"));assertEquals(5,((List<?>)service.rounds(p.id,11,20).get("items")).size());
        assertTrue(service.list().stream().filter(x->x.id.equals(p.id)).findFirst().orElseThrow().latestRound.assetHistory);
        assertEquals(3,ledger.page(p.id,null,null,null,null,null,null,1,20).get("total"));
        final long historyId=-latest;assertThrows(BusinessException.class,()->service.round(other.id,historyId));
        assertTrue(new String(reports.assetHistoryReport(p.id,historyId,"html"),java.nio.charset.StandardCharsets.UTF_8).contains("代码安全扫描整改报告"));
        assertTrue(reports.assetHistoryReport(p.id,historyId,"docx").length>1000);assertTrue(reports.assetHistoryReport(p.id,historyId,"pdf").length>1000);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM project_scan_round WHERE business_project_id=?",Integer.class,p.id));
        assertEquals(205,db.queryForObject("SELECT COUNT(*) FROM scan_run WHERE project_id=?",Integer.class,code));
        ProjectScanRound fresh=service.start(p.id,request(List.of(code),true));finish(fresh,"FAILED");
        assertEquals(fresh.id,service.latestRoundId(p.id));assertEquals(206L,service.rounds(p.id,1,20).get("total"));
        assertEquals(0,ledger.page(p.id,null,null,null,null,null,null,1,20).get("total"));
    }
    @Test void reportFiltersApplyBeforeRecentLimitAndRespectBusinessProjectAndAssetTogether() {
        BusinessProject p=service.save(null,"报告条件","");BusinessProject other=service.save(null,"其他报告","");Long a=asset("LOCAL"),b=asset("JAR_UPLOAD"),foreign=asset("LOCAL");service.attach(p.id,a);service.attach(p.id,b);service.attach(other.id,foreign);
        for(Long id:List.of(a,b,foreign)) db.update("INSERT INTO scan_run(project_id,scan_mode,workspace_mode,status,created_at) VALUES(?,'FULL','CURRENT','COMPLETED',?)",id,java.time.LocalDateTime.of(2020,1,1,0,0));
        for(int n=0;n<205;n++) db.update("INSERT INTO scan_run(project_id,scan_mode,workspace_mode,status,created_at) VALUES(?,'FULL','CURRENT','COMPLETED',NOW(3))",foreign);
        assertEquals(2,scanRecords.recentForReports(200,p.id,null).size());
        assertEquals(1,scanRecords.recentForReports(200,p.id,a).size());assertEquals(a,scanRecords.recentForReports(200,p.id,a).get(0).getProjectId());
        assertEquals(0,scanRecords.recentForReports(200,p.id,foreign).size());assertEquals(1,scanRecords.recentForReports(200,null,b).size());
    }
    @Test void ledgerSummaryCountsAllPagesAndSeparatesAssetScopeFromIssueFilters() {
        BusinessProject p=service.save(null,"台账条数","");Long a=asset("LOCAL"),b=asset("JAR_UPLOAD");service.attach(p.id,a);service.attach(p.id,b);
        ProjectScanRound round=service.start(p.id,request(List.of(a,b),true));finish(round,"COMPLETED","COMPLETED");findings(round.items.get(0),2);findings(round.items.get(1),1);
        db.update("UPDATE scan_finding_instance SET result_status='FIXED' WHERE scan_run_id=? ORDER BY id LIMIT 1",round.items.get(0).runId);
        db.update("UPDATE scan_finding_instance SET result_status='REOPENED' WHERE scan_run_id=?",round.items.get(1).runId);
        Map<String,Object> first=ledger.page(p.id,round.id,null,null,null,null,null,1,1);
        assertEquals(3,first.get("total"));assertEquals(Map.of("total",3,"newCount",2,"fixedCount",1),first.get("summary"));assertEquals(1,((List<?>)first.get("items")).size());
        Map<String,Object> filtered=ledger.page(p.id,round.id,null,"LOW",null,null,null,1,20);
        assertEquals(0,filtered.get("total"));assertEquals(first.get("summary"),filtered.get("summary"));
        Map<String,Object> scoped=ledger.page(p.id,round.id,a,null,null,null,"Test1",1,20);
        assertEquals(1,scoped.get("total"));assertEquals(Map.of("total",2,"newCount",1,"fixedCount",1),scoped.get("summary"));
    }
    @Test void ledgerPaginationCrossAssetIdentityAndFrozenReport() throws Exception {
        BusinessProject p=service.save(null,"报告<script>项目","");Long code=asset("LOCAL"),jar=asset("JAR_UPLOAD"),unselected=asset("LOCAL");service.attach(p.id,code);service.attach(p.id,jar);service.attach(p.id,unselected);
        ProjectScanRound r=service.start(p.id,request(List.of(code,jar),false));finish(r,"COMPLETED","COMPLETED");findings(r.items.get(0),205);findings(r.items.get(1),1);
        Map<String,Object> page=ledger.page(p.id,r.id,null,null,null,"INCLUDED",null,11,20);assertEquals(206,page.get("total"));assertEquals(6,((List<?>)page.get("items")).size());
        ProjectReportService.Snapshot frozen=reports.archive(p.id,r.id);assertEquals(206,frozen.includedCount);assertEquals(206,frozen.ledgerCount);assertEquals(206,frozen.severityCounts.get("HIGH"));assertTrue(frozen.html.contains("&lt;script&gt;"));assertTrue(frozen.html.contains("本轮未扫描"));
        db.update("UPDATE scan_triage SET triage_status='FALSE_POSITIVE' WHERE project_id=?",code);service.save(p.id,"修改后的项目","");
        ProjectReportService.Snapshot again=reports.archive(p.id,r.id);assertEquals(frozen.html,again.html);assertEquals(206,again.includedCount);
        try(XWPFDocument doc=new XWPFDocument(new ByteArrayInputStream(reports.docx(again)))) { assertTrue(doc.getTables().size()>1);assertTrue(doc.getParagraphs().stream().anyMatch(v->v.getText().contains("报告<script>项目"))); }
        assertTrue(reports.pdf(again).length>1000);
        ProjectScanRound next=service.start(p.id,request(List.of(code,jar),false));finish(next,"FAILED","FAILED");assertEquals(0,ledger.page(p.id,null,null,null,null,null,null,1,20).get("total"));
    }
}
