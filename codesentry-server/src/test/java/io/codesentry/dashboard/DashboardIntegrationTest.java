package io.codesentry.dashboard;

import static org.junit.jupiter.api.Assertions.*;

import io.codesentry.dashboard.dto.DashboardSummary;
import io.codesentry.dashboard.mapper.DashboardMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {
      "spring.main.web-application-type=none",
      "logging.level.root=WARN",
      "codesentry.ai.enabled=false"
    })
@EnabledIfEnvironmentVariable(
    named = "CODESENTRY_INTEGRATION_DB_URL",
    matches = ".*codesentry_business_test_.*")
@Transactional
class DashboardIntegrationTest {
  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> System.getenv("CODESENTRY_INTEGRATION_DB_URL"));
    r.add(
        "codesentry.storage.root",
        () -> System.getProperty("java.io.tmpdir") + "/codesentry-dashboard-test");
  }

  @Autowired JdbcTemplate db;
  @Autowired DashboardMapper dashboard;
  @Autowired org.mybatis.spring.SqlSessionTemplate session;

  @Test
  void countsAllHistoryWithoutJoinMultiplicationAndKeepsLegacyAssetAlias() {
    DashboardSummary before = dashboard.summary();
    db.update(
        "INSERT INTO business_project(name,created_at,updated_at) VALUES('统计测试',NOW(),NOW())");
    Long project = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    db.update(
        "INSERT INTO scan_project(project_name,source_type,enabled,created_at,updated_at) VALUES('代码统计','LOCAL',1,NOW(),NOW())");
    Long code = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    db.update(
        "INSERT INTO business_project_asset(asset_id,business_project_id) VALUES(?,?)",
        code,
        project);
    db.update(
        "INSERT INTO scan_project(project_name,source_type,enabled,created_at,updated_at) VALUES('JAR统计','JAR_UPLOAD',1,NOW(),NOW()),('停用资产','LOCAL',0,NOW(),NOW())");
    // 超过旧的 200 条窗口，同时保留三个活动状态，验证累计、活动数与资产关联互不放大。
    for (int i = 0; i < 205; i++) {
      String status = i == 0 ? "PENDING" : i == 1 ? "PREPARING" : i == 2 ? "RUNNING" : "COMPLETED";
      db.update(
          "INSERT INTO scan_run(project_id,scan_mode,workspace_mode,network_mode,status,new_findings,created_at) VALUES(?,'FULL','CURRENT','LOCAL',?,2,NOW())",
          code,
          status);
    }
    // JdbcTemplate 写入不会失效 MyBatis 会话缓存，显式清理后校验数据库的新统计。
    session.clearCache();
    DashboardSummary after = dashboard.summary();
    assertEquals(before.getBusinessProjects() + 1, after.getBusinessProjects());
    assertEquals(before.getAssets() + 2, after.getAssets());
    assertEquals(before.getCodeAssets() + 1, after.getCodeAssets());
    assertEquals(before.getJarAssets() + 1, after.getJarAssets());
    assertEquals(before.getUnassignedAssets() + 1, after.getUnassignedAssets());
    assertEquals(before.getScans() + 205, after.getScans());
    assertEquals(before.getRunningScans() + 3, after.getRunningScans());
    assertEquals(before.getNewFindings() + 410, after.getNewFindings());
    assertEquals(after.getAssets(), after.getProjects());
    assertEquals(after.getAssets(), after.getCodeAssets() + after.getJarAssets());
  }
}
