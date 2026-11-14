package io.codesentry.ai.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.ai.model.AiReviewJob;
import io.codesentry.ai.provider.AiReviewHttpClient;
import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.finding.service.*;
import io.codesentry.scan.service.ScanOrchestrator;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 真实 MySQL 验证预览不计费、显式提交、并发用量记录和来源可追溯的跨扫描复用。 */
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
class AiReviewCostControlIntegrationTest {
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> System.getenv("CODESENTRY_INTEGRATION_DB_URL"));
    r.add(
        "codesentry.storage.root",
        () -> System.getProperty("java.io.tmpdir") + "/codesentry-cost-tests");
  }

  @MockitoBean AiReviewHttpClient http;
  @MockitoBean ScanOrchestrator scans;
  @Autowired JdbcTemplate db;
  @Autowired AiReviewService ai;
  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean AiReviewCostControl costs;
  @Autowired CodeSentryProperties properties;
  @Autowired FindingPersistenceService findings;
  @Autowired FindingRuleCatalog catalog;
  @Autowired ObjectMapper json;

  @BeforeEach
  void setup() throws Exception {
    properties.getAi().setEnabled(true);
    properties.getAi().setModel("fixture-model");
    properties.getAi().setBaseUrl("http://fixture.invalid/v1");
    properties.getAi().setApiKey("fixture-only");
    db.update("DELETE FROM ai_review_result_cache");
    db.update("DELETE FROM ai_review_daily_budget");
    db.update("DELETE FROM finding_rule_classification");
    FindingClassification.configure(Map.of());
    AiReviewCostControl.Policy p = new AiReviewCostControl.Policy();
    p.paidEnabled = true;
    p.inputPricePerMillion = BigDecimal.ONE;
    p.outputPricePerMillion = BigDecimal.ONE;
    costs.savePolicy(p);
    when(http.postJson(anyString(), anyString(), anyString(), anyInt()))
        .thenReturn(
            "{\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":30},\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"verdict\\\":\\\"CONDITIONAL\\\",\\\"confidence\\\":0.9,\\\"reason\\\":\\\"需要核对输入来源\\\",\\\"recommendation\\\":\\\"使用参数化调用\\\",\\\"evidenceLines\\\":[1],\\\"missingEvidence\\\":[]}\"}}]}");
  }

  private Long asset() {
    db.update(
        "INSERT INTO scan_project(project_name,source_type,local_path,default_branch,enabled,created_at,updated_at) VALUES(?,'LOCAL','/tmp/fixture','main',1,NOW(3),NOW(3))",
        "cost-" + UUID.randomUUID());
    return db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
  }

  private Long run(Long asset, String commit) {
    db.update(
        "INSERT INTO scan_run(project_id,scan_mode,workspace_mode,status,scan_ref,rule_profile,scan_commit,repository_dirty,created_at) VALUES(?,'FULL','CURRENT','COMPLETED','main','LOCAL_OWASP',?,0,NOW(3))",
        asset,
        commit);
    return db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
  }

  private Long finding(
      Long asset, Long run, String scanner, String category, String rule, String fingerprint) {
    db.update(
        "INSERT INTO scan_finding(project_id,fingerprint,scanner,rule_id,category,severity,title,first_seen_at,last_seen_at) VALUES(?,?,?,?,?,'HIGH','fixture',NOW(3),NOW(3))",
        asset,
        fingerprint,
        scanner,
        rule,
        category);
    Long id = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    instance(id, run);
    return id;
  }

  private void instance(Long id, Long run) {
    db.update(
        "INSERT INTO scan_finding_instance(finding_id,scan_run_id,file_path,start_line,end_line,code_snippet,result_status,created_at) VALUES(?,?,'src/F.java',1,1,'statement.execute(userInput);','NEW',NOW(3))",
        id,
        run);
  }

  private AiReviewJob await(Long id) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
    AiReviewJob j;
    do {
      j = ai.requireJob(id);
      if (!Set.of("PENDING", "RUNNING").contains(j.getStatus())) return j;
      Thread.sleep(30);
    } while (System.nanoTime() < until);
    fail("review did not finish");
    return j;
  }

  @Test
  void previewSeparatesRulesWithoutCallsAndLegacyEntrypointsCannotBypassConsent() {
    Long a = asset(), r = run(a, "a".repeat(40));
    Long quality =
        finding(
            a,
            r,
            "Alibaba-P3C",
            "CODE_STANDARD",
            "Alibaba-P3C.LowerCamelCaseVariableNamingRule",
            "q");
    finding(a, r, "Alibaba-P3C", "CODE_STANDARD", "Alibaba-P3C.UnknownSecurityRule", "u");
    finding(a, r, "Semgrep", "security", "sql-injection", "s");
    finding(a, r, "Trivy", "VULNERABILITY", "CVE-fixture", "d");
    var p = ai.preview(r, new AiReviewCostControl.Options());
    assertEquals(4, p.total);
    assertEquals(1, p.quality);
    assertEquals(1, p.audit);
    assertEquals(1, p.localPrecheck);
    assertEquals(1, p.modelCandidates);
    assertEquals(2, p.estimatedRequests);
    verifyNoInteractions(http);
    assertThrows(BusinessException.class, () -> ai.startRunReview(r));
    assertThrows(BusinessException.class, () -> ai.reviewFinding(quality));
    assertThrows(BusinessException.class, () -> ai.retryFailedRunReview(r));
    assertThrows(BusinessException.class, () -> ai.startApproved(p.id, false));
    verifyNoInteractions(http);
    AiReviewCostControl.Options single = new AiReviewCostControl.Options();
    single.findingId = quality;
    assertEquals(0, ai.preview(r, single).modelCandidates);
  }

  @Test
  void explicitApprovalIsIdempotentAndStableCommitReusesBothRounds() throws Exception {
    Long a = asset(), r = run(a, "b".repeat(40));
    Long f = finding(a, r, "Semgrep", "security", "sql-injection", "s");
    var p = ai.preview(r, new AiReviewCostControl.Options());
    AiReviewJob first = ai.startApproved(p.id, true);
    assertEquals("COMPLETED", await(first.getId()).getStatus());
    assertEquals(first.getId(), ai.startApproved(p.id, true).getId());
    verify(http, times(2)).postJson(anyString(), anyString(), anyString(), anyInt());
    var same = ai.preview(r, new AiReviewCostControl.Options());
    assertEquals(1, same.alreadyReviewed);
    assertEquals(0, same.modelCandidates);
    var freePolicy = costs.policy();
    freePolicy.paidEnabled = false;
    costs.savePolicy(freePolicy);
    Long next = run(a, "b".repeat(40));
    instance(f, next);
    var reused = ai.preview(next, new AiReviewCostControl.Options());
    assertEquals(1, reused.reusable);
    assertEquals(0, reused.modelCandidates);
    AiReviewJob second = ai.startApproved(reused.id, false);
    assertEquals("COMPLETED", await(second.getId()).getStatus());
    verify(http, times(2)).postJson(anyString(), anyString(), anyString(), anyInt());
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM ai_review_reuse_log WHERE job_id=?",
            Integer.class,
            second.getId()));
    assertEquals(0, ((Number) costs.usage(second.getId()).get("requests_used")).intValue());
    Long changed = run(a, "c".repeat(40));
    instance(f, changed);
    assertEquals(0, ai.preview(changed, new AiReviewCostControl.Options()).reusable);
    db.update("UPDATE scan_run SET repository_dirty=1 WHERE id=?", next);
    assertEquals(0, ai.preview(next, new AiReviewCostControl.Options()).reusable);
    Long other = asset(), otherRun = run(other, "b".repeat(40));
    finding(other, otherRun, "Semgrep", "security", "sql-injection", "s");
    assertEquals(0, ai.preview(otherRun, new AiReviewCostControl.Options()).reusable);
    properties.getAi().setModel("other-fixture-model");
    var changedModel = ai.preview(changed, new AiReviewCostControl.Options());
    assertEquals(0, changedModel.reusable);
    assertNotEquals(p.modelSignature, changedModel.modelSignature);
  }

  @Test
  void legacyBudgetsAndMissingPricesDoNotBlockEitherRound() throws Exception {
    Long a = asset(), r = run(a, "d".repeat(40));
    finding(a, r, "Semgrep", "security", "sql-injection", "s");
    var legacy = costs.policy();
    legacy.paidEnabled = false;
    legacy.maxRequests = 1;
    legacy.maxInputTokens = 1;
    legacy.maxOutputTokens = 1;
    legacy.maxCost = new BigDecimal("0.00000001");
    legacy.dailyCostLimit = legacy.maxCost;
    legacy.inputPricePerMillion = BigDecimal.ZERO;
    legacy.outputPricePerMillion = BigDecimal.ZERO;
    costs.savePolicy(legacy);
    AiReviewCostControl.Options o = new AiReviewCostControl.Options();
    o.maxRequests = 1;
    o.maxInputTokens = 1;
    o.maxOutputTokens = 1;
    o.maxCost = legacy.maxCost;
    var p = ai.preview(r, o);
    AiReviewJob j = await(ai.startApproved(p.id, true).getId());
    assertEquals("COMPLETED", j.getStatus());
    assertEquals(0, j.getFailedFindings());
    verify(http, times(2)).postJson(anyString(), anyString(), anyString(), anyInt());
    assertEquals(2, ((Number) costs.usage(j.getId()).get("requests_used")).intValue());
    assertEquals(
        2,
        db.queryForObject(
            "SELECT COUNT(*) FROM scan_ai_review WHERE review_job_id=?", Integer.class, j.getId()));
  }

  @Test
  void optionalCacheFailureDoesNotRepeatPaidReview() throws Exception {
    Long a = asset(), r = run(a, "8".repeat(40));
    finding(a, r, "Semgrep", "security", "sql-injection", "s");
    doThrow(new IllegalStateException("fixture cache unavailable"))
        .when(costs)
        .cache(anyString(), any(), any(), any());
    var plan = ai.preview(r, new AiReviewCostControl.Options());
    AiReviewJob job = await(ai.startApproved(plan.id, true).getId());
    assertEquals("COMPLETED", job.getStatus());
    verify(http, times(2)).postJson(anyString(), anyString(), anyString(), anyInt());
  }

  @Test
  void modelTruncationAndFailureRetriesAreAlsoMetered() throws Exception {
    Long a = asset(), r = run(a, "9".repeat(40));
    finding(a, r, "Semgrep", "security", "sql-injection", "s");
    when(http.postJson(anyString(), anyString(), anyString(), anyInt()))
        .thenReturn("{\"choices\":[{\"finish_reason\":\"length\"}]}");
    AiReviewCostControl.Options o = new AiReviewCostControl.Options();
    o.maxRequests = 3;
    var p = ai.preview(r, o);
    AiReviewJob job = await(ai.startApproved(p.id, true).getId());
    assertEquals("FAILED", job.getStatus());
    assertEquals(1, job.getFailedFindings());
    verify(http, times(4)).postJson(anyString(), anyString(), anyString(), anyInt());
    assertEquals(4, ((Number) costs.usage(job.getId()).get("requests_used")).intValue());
    assertEquals(4, ((Number) costs.usage(job.getId()).get("usage_missing")).intValue());
  }

  @Test
  void concurrentUsageIsCountedWithoutEnforcingLegacyLimits() throws Exception {
    Long a = asset(), r = run(a, "e".repeat(40));
    finding(a, r, "Semgrep", "security", "sql-injection", "s");
    var p = ai.preview(r, new AiReviewCostControl.Options());
    p.budget.maxRequests = 2;
    db.update(
        "INSERT INTO scan_ai_review_job(scan_run_id,provider,model,status,total_findings,created_at) VALUES(?,'fixture','fixture-model','COMPLETED',1,NOW(3))",
        r);
    Long job = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    costs.bind(p, job);
    ExecutorService executor = Executors.newFixedThreadPool(8);
    try {
      List<Future<Boolean>> fs = new ArrayList<>();
      for (int n = 0; n < 8; n++)
        fs.add(
            executor.submit(
                () -> {
                  try {
                    costs.reserve(job, "fixture", 1024);
                    return true;
                  } catch (AiReviewCostControl.BudgetExceeded e) {
                    return false;
                  }
                }));
      int success = 0;
      for (Future<Boolean> f : fs) if (f.get()) success++;
      assertEquals(8, success);
    } finally {
      executor.shutdownNow();
    }
    assertEquals(8, ((Number) costs.usage(job).get("requests_used")).intValue());
    var policy = costs.policy();
    policy.dailyCostLimit = new BigDecimal("0.00000001");
    costs.savePolicy(policy);
    var next = ai.preview(r, new AiReviewCostControl.Options());
    assertEquals("COMPLETED", await(ai.startApproved(next.id, true).getId()).getStatus());
    verify(http, times(2)).postJson(anyString(), anyString(), anyString(), anyInt());
  }

  @Test
  void expiredOrChangedPlanCannotStartAndUnknownRuleCanBeClassified() {
    Long a = asset(), r = run(a, "f".repeat(40));
    finding(a, r, "Alibaba-P3C", "CODE_STANDARD", "Alibaba-P3C.SqlInjectionRule", "s");
    var p = ai.preview(r, new AiReviewCostControl.Options());
    assertEquals(1, p.audit);
    catalog.save("Alibaba-P3C.SqlInjectionRule", "SECURITY", "涉及 SQL 输入来源，需要安全核实");
    assertThrows(BusinessException.class, () -> ai.startApproved(p.id, true));
    var next = ai.preview(r, new AiReviewCostControl.Options());
    assertEquals(1, next.modelCandidates);
    db.update(
        "UPDATE ai_review_plan SET expires_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE id=?",
        next.id);
    assertThrows(BusinessException.class, () -> ai.startApproved(next.id, true));
    verifyNoInteractions(http);
  }

  @AfterEach
  void clearClassifications() {
    db.update("DELETE FROM finding_rule_classification");
    FindingClassification.configure(Map.of());
  }
}
