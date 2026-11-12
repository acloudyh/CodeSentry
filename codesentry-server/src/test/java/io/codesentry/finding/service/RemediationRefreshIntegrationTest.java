package io.codesentry.finding.service;
import io.codesentry.scan.mapper.ScanRunMapper;
import io.codesentry.scan.model.*;
import io.codesentry.scan.service.ScanOrchestrator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.main.web-application-type=none","logging.level.root=WARN","codesentry.ai.enabled=false"})
@EnabledIfEnvironmentVariable(named="CODESENTRY_INTEGRATION_DB_URL",matches=".*codesentry_business_test_.*")
@DirtiesContext
class RemediationRefreshIntegrationTest {
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) { r.add("spring.datasource.url",()->System.getenv("CODESENTRY_INTEGRATION_DB_URL"));r.add("codesentry.storage.root",()->System.getProperty("java.io.tmpdir")+"/codesentry-online-recheck-test"); }
    @MockBean RemediationHttpClient remote;
    @MockBean ScanOrchestrator scanners;
    @Autowired JdbcTemplate db;
    @Autowired RemediationRefreshService refresh;
    @Autowired ScanRunMapper runs;
    @Test void slowRecheckReturnsImmediatelyAndPreservesScanFindingsAndReviewIdentity() throws Exception {
        db.update("INSERT INTO scan_project(project_name,source_type,enabled,target_java_version,created_at,updated_at) VALUES('联网补查测试','LOCAL',1,8,NOW(),NOW())");Long asset=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        db.update("INSERT INTO scan_run(project_id,scan_mode,workspace_mode,network_mode,remediation_status,status,total_findings,created_at,finished_at) VALUES(?,'FULL','CURRENT','ONLINE','PARTIAL','COMPLETED',1,NOW(),NOW())",asset);Long id=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        db.update("INSERT INTO scan_finding(project_id,fingerprint,scanner,rule_id,category,severity,title,first_seen_at,last_seen_at) VALUES(?,SHA2(UUID(),256),'Trivy','CVE-2023-1111','VULNERABILITY','HIGH','补查测试',NOW(),NOW())",asset);Long finding=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        db.update("INSERT INTO scan_finding_instance(finding_id,scan_run_id,file_path,code_snippet,result_status,created_at) VALUES(?,?,'sample.jar','example:library@2.0.0','NEW',NOW())",finding,id);
        db.update("INSERT INTO scan_triage(project_id,finding_id,triage_status,reviewer,created_at,updated_at) VALUES(?,?,'CONFIRMED','测试',NOW(),NOW())",asset,finding);
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        when(remote.query("example:library","2.0.0")).thenAnswer(c->{started.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return new ObjectMapper().createObjectNode();});
        int count=db.queryForObject("SELECT COUNT(*) FROM scan_run",Integer.class);
        try {
            ScanRun queued=assertTimeout(java.time.Duration.ofSeconds(2),()->refresh.start(id));assertEquals("PENDING",queued.getRemediationStatus());assertTrue(started.await(5,TimeUnit.SECONDS));
            assertTrue(java.util.Set.of("PENDING","RUNNING").contains(refresh.start(id).getRemediationStatus()));release.countDown();
            for(int n=0;n<100 && !"COMPLETED".equals(runs.findById(id).getRemediationStatus());n++) Thread.sleep(50);
            assertEquals("COMPLETED",runs.findById(id).getRemediationStatus());assertEquals(ScanRunStatus.COMPLETED,runs.findById(id).getStatus());assertEquals(count,db.queryForObject("SELECT COUNT(*) FROM scan_run",Integer.class));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM scan_finding_instance WHERE finding_id=? AND scan_run_id=?",Integer.class,finding,id));assertEquals("CONFIRMED",db.queryForObject("SELECT triage_status FROM scan_triage WHERE finding_id=?",String.class,finding));verify(remote,times(1)).query("example:library","2.0.0");verifyNoInteractions(scanners);
        } finally {release.countDown();}
    }
}
