package io.codesentry.report.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.codesentry.business.ProjectReportService;
import io.codesentry.common.error.BusinessException;
import io.codesentry.report.controller.ReportArchiveController;
import io.codesentry.report.dto.ArchiveRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** 在真实 MySQL 唯一键约束下验证归档幂等身份、冲突和有效期；不触碰生产库。 */
@SpringBootTest(
    properties = {
      "spring.main.web-application-type=none",
      "logging.level.root=WARN",
      "codesentry.runtime.worker-enabled=false"
    })
@EnabledIfEnvironmentVariable(
    named = "CODESENTRY_INTEGRATION_DB_URL",
    matches = ".*codesentry_business_test_.*")
@Transactional
class ReportArchiveIntegrationTest {
  @DynamicPropertySource
  static void props(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", () -> System.getenv("CODESENTRY_INTEGRATION_DB_URL"));
    properties.add(
        "codesentry.storage.root",
        () -> System.getProperty("java.io.tmpdir") + "/codesentry-archive-test");
  }

  @MockitoBean RemediationReportService reports;
  @MockitoBean ProjectReportService projects;
  @Autowired ReportArchiveService archives;
  @Autowired JdbcTemplate db;

  @Test
  void sameKeySameSourceReplaysReceiptAndDifferentSourceIsConflict() {
    String key = UUID.randomUUID().toString();
    ArchiveRequest request = new ArchiveRequest();
    request.scanRunId = 101L;
    ReportArchiveController controller = new ReportArchiveController(archives);
    var first = controller.create(request, key);
    var second = controller.create(request, key);
    assertEquals(201, first.getStatusCode().value());
    assertEquals(first.getHeaders().getLocation(), second.getHeaders().getLocation());
    assertEquals("/api/v1/reports/scan-runs/101/html", first.getBody().reportUrl);
    request.scanRunId = 102L;
    BusinessException conflict =
        assertThrows(BusinessException.class, () -> archives.archive(request, key));
    assertEquals(409, conflict.getStatus().value());
    verify(reports, never()).archiveFindings(102L);
    assertTrue(
        db.queryForObject(
                "SELECT MIN(TIMESTAMPDIFF(HOUR,NOW(3),expires_at)) FROM http_idempotency",
                Integer.class)
            >= 23);
  }

  @Test
  void projectSourceAndExpiredKeyCanBeReusedWithoutSavingRawKeys() {
    String key = UUID.randomUUID().toString();
    ArchiveRequest request = new ArchiveRequest();
    request.businessProjectId = 1L;
    request.roundId = 2L;
    assertEquals(
        "/api/v1/business-projects/1/rounds/2/report/html", archives.archive(request, key));
    verify(projects).archive(1L, 2L);
    assertFalse(
        db.queryForList("SELECT key_hash FROM http_idempotency", String.class).contains(key));
    db.update("UPDATE http_idempotency SET expires_at=DATE_SUB(NOW(3),INTERVAL 1 SECOND)");
    request.roundId = 3L;
    archives.archive(request, key);
    verify(projects).archive(1L, 3L);
  }

  @Test
  void invalidSourceAndIdentityFailBeforeArchiveSideEffects() {
    ArchiveRequest request = new ArchiveRequest();
    assertThrows(BusinessException.class, () -> archives.archive(request, "invalid"));
    assertThrows(BusinessException.class, () -> archives.archive(request, "valid-key"));
    request.scanRunId = 1L;
    request.businessProjectId = 2L;
    assertThrows(BusinessException.class, () -> archives.archive(request, "valid-key"));
    verifyNoInteractions(reports, projects);
  }
}
