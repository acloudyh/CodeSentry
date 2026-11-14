package io.codesentry.report.service;

import io.codesentry.business.ProjectReportService;
import io.codesentry.common.error.BusinessException;
import io.codesentry.report.dto.ArchiveRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 显式报告归档边界；幂等身份持久化 24 小时，渲染不处于数据库事务中。 */
@Service
public class ReportArchiveService {
  private final JdbcTemplate db;
  private final RemediationReportService reports;
  private final ProjectReportService projects;

  public ReportArchiveService(
      JdbcTemplate db, RemediationReportService reports, ProjectReportService projects) {
    this.db = db;
    this.reports = reports;
    this.projects = projects;
  }

  public String archive(ArchiveRequest request, String key) {
    if (key == null || !key.matches("[A-Za-z0-9_-]{8,128}"))
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "请提供 8 至 128 位合法幂等请求标识");
    if (!request.isValidSource())
      throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARCHIVE_SOURCE", "归档来源不完整");
    String source = digest(request.source()), identity = digest("report-archives:" + key);
    // 两条语句均为短事务；重复键并发由主键串行仲裁，不能用 JVM Map 替代跨实例幂等。
    db.update("DELETE FROM http_idempotency WHERE expires_at < NOW(3) LIMIT 1000");
    db.update(
        "INSERT IGNORE INTO http_idempotency(key_hash,request_hash,expires_at) VALUES(?,?,DATE_ADD(NOW(3), INTERVAL 24 HOUR))",
        identity,
        source);
    String previous =
        db.queryForObject(
            "SELECT request_hash FROM http_idempotency WHERE key_hash=?", String.class, identity);
    if (!source.equals(previous))
      throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "相同幂等标识不能用于不同归档来源");
    if (request.scanRunId != null) {
      reports.archiveFindings(request.scanRunId);
      return "/api/v1/reports/scan-runs/" + request.scanRunId + "/html";
    }
    projects.archive(request.businessProjectId, request.roundId);
    return "/api/v1/business-projects/"
        + request.businessProjectId
        + "/rounds/"
        + request.roundId
        + "/report/html";
  }

  private static String digest(String text) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      StringBuilder value = new StringBuilder();
      for (byte b : hash) value.append(String.format("%02x", b & 255));
      return value.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
