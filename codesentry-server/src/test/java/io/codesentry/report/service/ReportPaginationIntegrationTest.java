package io.codesentry.report.service;

import io.codesentry.report.dto.ReportPage;
import io.codesentry.scan.service.ScanOrchestrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 验证报告历史超过 200 条后仍可查询，并核对项目与资产的交叉筛选边界。 */
@SpringBootTest(properties={"spring.main.web-application-type=none","logging.level.root=WARN","codesentry.ai.enabled=false"})
@EnabledIfEnvironmentVariable(named="CODESENTRY_INTEGRATION_DB_URL",matches=".*codesentry_business_test_.*")
@Transactional
class ReportPaginationIntegrationTest {
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("CODESENTRY_INTEGRATION_DB_URL"));
        r.add("codesentry.storage.root",()->System.getProperty("java.io.tmpdir")+"/codesentry-report-page-test");
    }
    @MockBean ScanOrchestrator scanners;
    @Autowired JdbcTemplate db;
    @Autowired RemediationReportService reports;

    @Test void reachesOldestReportBeyondTwoHundredWithStableOrderAndFullFilteredTotal() {
        db.update("INSERT INTO business_project(name,created_at,updated_at) VALUES('分页测试',NOW(),NOW())");
        Long project=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        db.update("INSERT INTO business_project(name,created_at,updated_at) VALUES('无报告项目',NOW(),NOW())");
        Long emptyProject=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        Long code=asset("分页代码","LOCAL",project),jar=asset("分页JAR","JAR_UPLOAD",project);
        List<Long> ids=new ArrayList<>();
        for(int i=0;i<205;i++) {
            db.update("INSERT INTO scan_run(project_id,scan_mode,workspace_mode,network_mode,status,new_findings,created_at) VALUES(?,'FULL','CURRENT','LOCAL',?,0,'2026-11-12 12:00:00')",i<200?code:jar,i==204?"PARTIAL":"COMPLETED");
            ids.add(db.queryForObject("SELECT LAST_INSERT_ID()",Long.class));
        }
        db.update("INSERT INTO scan_run(project_id,scan_mode,workspace_mode,network_mode,status,created_at) VALUES(?,'FULL','CURRENT','LOCAL','RUNNING',NOW())",code);
        ReportPage oldest=reports.page(21,10,project,null);
        assertEquals(205,oldest.getTotal());assertEquals(21,oldest.getPage());assertEquals(5,oldest.getItems().size());
        assertEquals(ids.get(4),oldest.getItems().get(0).getId());assertEquals(ids.get(0),oldest.getItems().get(4).getId());
        ReportPage first=reports.page(1,20,project,null);
        assertEquals(ids.get(204),first.getItems().get(0).getId());assertEquals(20,first.getItems().size());
        assertEquals(200,reports.page(1,20,project,code).getTotal());
        ReportPage jars=reports.page(0,10000,project,jar);assertEquals(5,jars.getTotal());assertEquals(1,jars.getPage());assertEquals(100,jars.getPageSize());
        ReportPage clamped=reports.page(Integer.MAX_VALUE,20,project,null);assertEquals(11,clamped.getPage());assertEquals(5,clamped.getItems().size());
        ReportPage noMatch=reports.page(5,20,emptyProject,code);assertEquals(0,noMatch.getTotal());assertEquals(1,noMatch.getPage());assertTrue(noMatch.getItems().isEmpty());
    }

    /** 创建归属于测试项目的资产，不通过扫描服务启动任何外部工具。 */
    private Long asset(String name,String type,Long owner) {
        db.update("INSERT INTO scan_project(project_name,source_type,enabled,created_at,updated_at) VALUES(?,?,1,NOW(),NOW())",name,type);
        Long id=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        db.update("INSERT INTO business_project_asset(asset_id,business_project_id) VALUES(?,?)",id,owner);return id;
    }
}
