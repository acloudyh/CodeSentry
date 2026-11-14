package io.codesentry.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.codesentry.common.error.BusinessException;
import io.codesentry.project.mapper.ScanProjectMapper;
import io.codesentry.project.model.*;
import io.codesentry.report.service.RemediationReportService;
import io.codesentry.scan.model.*;
import io.codesentry.scan.service.ScanOrchestrator;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 真实数据库验证排队、固定来源、三种 ZIP 布局、失败不提供半包及重放幂等。 */
@SpringBootTest(
    properties = {
      "spring.main.web-application-type=none",
      "logging.level.root=WARN",
      "codesentry.ai.enabled=false"
    })
@DirtiesContext
@EnabledIfEnvironmentVariable(
    named = "CODESENTRY_INTEGRATION_DB_URL",
    matches = ".*codesentry_business_test_.*")
class ProjectReportPackageIntegrationTest {
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> System.getenv("CODESENTRY_INTEGRATION_DB_URL"));
    r.add(
        "codesentry.storage.root",
        () -> System.getProperty("java.io.tmpdir") + "/codesentry-package-integration");
  }

  @MockitoBean ScanOrchestrator scans;
  @MockitoBean RemediationReportService reports;
  @Autowired ProjectReportPackageService packages;
  @Autowired BusinessProjectService projects;
  @Autowired ScanProjectMapper assets;
  @Autowired JdbcTemplate db;
  private String suffix;

  @BeforeEach
  void setup() {
    suffix = "-" + UUID.randomUUID();
    render();
  }

  private void render() {
    doAnswer(
            call ->
                documents(
                    call.getArgument(0),
                    call.getArgument(1),
                    call.getArgument(2),
                    call.getArgument(3)))
        .when(reports)
        .exportAssetDocuments(anyLong(), any(), anyBoolean(), anyBoolean());
  }

  private RemediationReportService.AssetDocuments documents(
      Long run, ScanProject asset, boolean word, boolean pdf) {
    RemediationReportService.AssetDocuments d = new RemediationReportService.AssetDocuments();
    if (word)
      d.word = ("word:" + run + ":" + asset.getProjectName()).getBytes(StandardCharsets.UTF_8);
    if (pdf) d.pdf = ("pdf:" + run + ":" + asset.getProjectName()).getBytes(StandardCharsets.UTF_8);
    return d;
  }

  private Long asset(String name, String type) {
    ScanProject a = new ScanProject();
    a.setProjectName(name + suffix);
    a.setSourceType(ProjectSourceType.valueOf(type));
    a.setLocalPath("/tmp/fixture.jar");
    a.setEnabled(true);
    a.setDefaultBranch("main");
    a.setCreatedAt(LocalDateTime.now());
    a.setUpdatedAt(a.getCreatedAt());
    assets.insert(a);
    return a.getId();
  }

  private ProjectScanRound round(Long project, List<Long> ids, boolean full) {
    ProjectScanRequest request = new ProjectScanRequest();
    request.requestKey = UUID.randomUUID().toString();
    request.networkMode = NetworkMode.LOCAL;
    request.fullScope = full;
    request.assets = new ArrayList<>();
    for (Long id : ids) {
      ProjectScanRequest.Target t = new ProjectScanRequest.Target();
      t.assetId = id;
      t.workspaceMode =
          assets.findById(id).getSourceType() == ProjectSourceType.JAR_UPLOAD
              ? WorkspaceMode.JAR_UPLOAD
              : WorkspaceMode.CURRENT;
      request.assets.add(t);
    }
    ProjectScanRound scope = projects.start(project, request);
    // 上传文件校验由原扫描测试覆盖；此处为未创建子任务的 JAR 项补齐隔离的来源记录。
    for (ProjectScanItem item : scope.items)
      if (item.runId == null) {
        db.update(
            "INSERT INTO scan_run(project_id,scan_mode,workspace_mode,status,scan_ref,rule_profile,created_at) VALUES(?,'FULL',?,'PENDING','main','LOCAL_OWASP',NOW(3))",
            item.assetId,
            item.workspaceMode);
        item.runId =
            db.queryForObject(
                "SELECT MAX(id) FROM scan_run WHERE project_id=?", Long.class, item.assetId);
        db.update(
            "UPDATE project_scan_item SET run_id=?,error_message=NULL WHERE id=?",
            item.runId,
            item.id);
      }
    return scope;
  }

  private void finish(ProjectScanRound r, String... states) {
    for (int i = 0; i < states.length; i++)
      db.update(
          "UPDATE scan_run SET status=?,finished_at=NOW(3) WHERE id=?",
          states[i],
          r.items.get(i).runId);
  }

  private ProjectReportPackageService.Job await(Long p, String id) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
    do {
      packages.dispatch();
      var j = packages.require(p, id);
      if (!Set.of("PENDING", "RUNNING").contains(j.status)) return j;
      Thread.sleep(25);
    } while (System.nanoTime() < end);
    fail("ZIP task did not complete");
    return null;
  }

  private Map<String, String> entries(Path path) throws Exception {
    Map<String, String> result = new LinkedHashMap<>();
    try (ZipInputStream in =
        new ZipInputStream(Files.newInputStream(path), StandardCharsets.UTF_8)) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        assertFalse(entry.getName().startsWith("/"));
        assertFalse(entry.getName().contains("../"));
        assertNull(
            result.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8)));
      }
    }
    return result;
  }

  @Test
  void allFormatsIncludeExactlyFourSelectedAssetReportsAndReplayReturnsSameJob() throws Exception {
    var p = projects.save(null, "CCUS", "");
    List<Long> ids =
        List.of(
            asset("ccus-web-prod", "LOCAL"),
            asset("ccus-app-web-prod", "LOCAL"),
            asset("ccus-service-prod", "JAR_UPLOAD"),
            asset("ccus-app-service-prod", "JAR_UPLOAD"));
    for (Long id : ids) projects.attach(p.id, id);
    var r = round(p.id, ids, true);
    finish(r, "COMPLETED", "COMPLETED", "COMPLETED", "COMPLETED");
    for (String format : List.of("PDF", "WORD", "BOTH")) {
      String key = UUID.randomUUID().toString();
      var job = packages.start(p.id, r.id, format, key);
      job = await(p.id, job.id);
      assertEquals("COMPLETED", job.status);
      assertEquals("CCUS安全扫描.zip", job.fileName);
      assertEquals("BOTH".equals(format) ? 8 : 4, job.totalFiles);
      assertEquals(job.totalFiles, job.completedFiles);
      Map<String, String> files = entries(packages.download(p.id, job.id));
      assertEquals(job.totalFiles, files.size());
      for (ProjectScanItem item : r.items) {
        if (!"PDF".equals(format))
          assertEquals(
              "word:" + item.runId + ":" + item.assetName,
              files.get(("BOTH".equals(format) ? "Word/" : "") + item.assetName + "安全扫描.docx"));
        if (!"WORD".equals(format))
          assertEquals(
              "pdf:" + item.runId + ":" + item.assetName,
              files.get(("BOTH".equals(format) ? "PDF/" : "") + item.assetName + "安全扫描.pdf"));
      }
      assertEquals(job.id, packages.start(p.id, r.id, format, key).id);
      assertEquals(job.id, packages.latest(p.id, r.id, format).id);
    }
    verify(reports, times(12)).exportAssetDocuments(anyLong(), any(), anyBoolean(), anyBoolean());
  }

  @Test
  void partialScopeDocumentsMissingAssetsAndNamesCannotEscapeOrOverwrite() throws Exception {
    var p = projects.save(null, "范围项目", "");
    List<Long> ids =
        List.of(
            asset("../同名", "LOCAL"),
            asset("..\\同名", "LOCAL"),
            asset("失败项", "LOCAL"),
            asset("未选中", "LOCAL"));
    for (Long id : ids) projects.attach(p.id, id);
    var r = round(p.id, ids.subList(0, 3), false);
    finish(r, "COMPLETED", "PARTIAL", "FAILED");
    var job = await(p.id, packages.start(p.id, r.id, "WORD", UUID.randomUUID().toString()).id);
    assertEquals("COMPLETED", job.status);
    Map<String, String> files = entries(packages.download(p.id, job.id));
    assertEquals(3, files.size());
    assertTrue(files.containsKey("导出说明.txt"));
    assertTrue(files.get("导出说明.txt").contains("未选中"));
    assertTrue(files.get("导出说明.txt").contains("FAILED"));
    assertTrue(files.get("导出说明.txt").contains("PARTIAL"));
    assertTrue(files.keySet().stream().anyMatch(n -> n.contains("资产")));
    assertFalse(files.keySet().stream().anyMatch(n -> n.startsWith("失败项")));
    assertThrows(
        BusinessException.class,
        () -> packages.download(projects.save(null, "其他项目", "").id, job.id));
  }

  @Test
  void failuresDoNotPublishHalfZipAndExplicitRetryCreatesNewTask() throws Exception {
    var p = projects.save(null, "失败打包", "");
    Long id = asset("服务", "LOCAL");
    projects.attach(p.id, id);
    var r = round(p.id, List.of(id), true);
    finish(r, "COMPLETED");
    doThrow(new IllegalStateException("fixture renderer failure"))
        .when(reports)
        .exportAssetDocuments(anyLong(), any(), anyBoolean(), anyBoolean());
    String key = UUID.randomUUID().toString();
    var failed = await(p.id, packages.start(p.id, r.id, "BOTH", key).id);
    assertEquals("FAILED", failed.status);
    assertThrows(BusinessException.class, () -> packages.download(p.id, failed.id));
    assertEquals(failed.id, packages.start(p.id, r.id, "BOTH", key).id);
    render();
    var retry = await(p.id, packages.start(p.id, r.id, "BOTH", UUID.randomUUID().toString()).id);
    assertNotEquals(failed.id, retry.id);
    assertEquals("COMPLETED", retry.status);
  }

  @Test
  void runningEmptyWrongProjectAndInvalidFormatsCannotExport() {
    var p = projects.save(null, "状态校验", "");
    Long id = asset("资产", "LOCAL");
    projects.attach(p.id, id);
    var r = round(p.id, List.of(id), true);
    assertThrows(BusinessException.class, () -> packages.start(p.id, r.id, "WORD", "running"));
    finish(r, "FAILED");
    assertThrows(BusinessException.class, () -> packages.start(p.id, r.id, "WORD", "failed"));
    assertThrows(BusinessException.class, () -> packages.start(p.id, r.id, "ZIP", "invalid"));
    assertThrows(
        BusinessException.class,
        () -> packages.start(projects.save(null, "其他", "").id, r.id, "PDF", "owner"));
  }

  @Test
  void simultaneousRequestsAreIdempotentAndRestartRetainsCompletedFiles() throws Exception {
    var p = projects.save(null, "幂等项目", "");
    Long id = asset("服务", "LOCAL");
    projects.attach(p.id, id);
    var r = round(p.id, List.of(id), true);
    finish(r, "COMPLETED");
    String key = UUID.randomUUID().toString();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    String job;
    try {
      var a = pool.submit(() -> packages.start(p.id, r.id, "PDF", key));
      var b = pool.submit(() -> packages.start(p.id, r.id, "PDF", key));
      job = a.get().id;
      assertEquals(job, b.get().id);
    } finally {
      pool.shutdownNow();
    }
    assertEquals("COMPLETED", await(p.id, job).status);
    Path zip = packages.download(p.id, job);
    assertTrue(Files.isRegularFile(zip));
    String interrupted = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO project_report_package(id,business_project_id,round_id,request_key,report_format,status,file_name,scope_json,total_files,created_at) VALUES(?,?,?,?,'PDF','RUNNING','interrupted.zip','{}',1,NOW(3))",
        interrupted,
        p.id,
        r.id,
        UUID.randomUUID().toString());
    packages.recover();
    assertEquals("FAILED", packages.require(p.id, interrupted).status);
    assertTrue(Files.isRegularFile(packages.download(p.id, job)));
    assertThrows(BusinessException.class, () -> packages.start(p.id, r.id, "WORD", key));
  }
}
