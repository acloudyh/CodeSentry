package io.codesentry.report.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.codesentry.finding.model.FindingView;
import io.codesentry.finding.service.FindingPersistenceService;
import io.codesentry.scan.engine.FindingFingerprintService;
import io.codesentry.scan.mapper.ScanRunMapper;
import io.codesentry.scan.model.*;
import io.codesentry.scan.service.ScanToolRunService;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReportAcceptanceServiceTest {
  final ScanRunMapper runs = mock(ScanRunMapper.class);
  final FindingPersistenceService findings = mock(FindingPersistenceService.class);
  final RemediationReportService reports = mock(RemediationReportService.class);
  final ScanToolRunService tools = mock(ScanToolRunService.class);
  final ReportAcceptanceService service =
      new ReportAcceptanceService(reports, runs, findings, tools, new FindingFingerprintService());

  ScanRun run(long id) {
    ScanRun r = new ScanRun();
    r.setId(id);
    r.setProjectId(1L);
    r.setRuleProfile("LOCAL_OWASP");
    r.setStatus(ScanRunStatus.COMPLETED);
    r.setScanMode(ScanMode.FULL);
    return r;
  }

  FindingView finding(String fingerprint, String rule, String path) {
    FindingView f = new FindingView();
    f.setId(10L);
    f.setFingerprint(fingerprint);
    f.setScanner("Semgrep");
    f.setRuleId(rule);
    f.setFilePath(path);
    return f;
  }

  @Test
  void distinguishesExactRiskFromChangedCodeAndNoHit() {
    FindingView f = finding("old", "sql-injection", "a.java");
    ScanRun source = run(1), target = run(2);
    assertEquals(
        "STILL_PRESENT", service.status(f, f, source, target, List.of(f), Set.of("semgrep")));
    assertEquals(
        "NEEDS_REVIEW",
        service.status(
            f,
            f,
            source,
            target,
            List.of(finding("changed", "sql-injection", "./a.java")),
            Set.of("semgrep")));
    assertEquals(
        "NOT_DETECTED", service.status(f, f, source, target, List.of(), Set.of("semgrep")));
  }

  @Test
  void missingScannerPartialCancelledAndOtherProfilesNeverPass() {
    FindingView f = finding("old", "sql", "a.java");
    ScanRun source = run(1), target = run(2);
    assertEquals("UNVERIFIED", service.status(f, f, source, target, List.of(), Set.of()));
    for (ScanRunStatus status :
        List.of(
            ScanRunStatus.PARTIAL,
            ScanRunStatus.FAILED,
            ScanRunStatus.CANCELLED,
            ScanRunStatus.RUNNING,
            ScanRunStatus.PENDING)) {
      target.setStatus(status);
      assertEquals(
          "UNVERIFIED", service.status(f, f, source, target, List.of(), Set.of("semgrep")));
    }
    target.setStatus(ScanRunStatus.COMPLETED);
    target.setRuleProfile("COMPREHENSIVE");
    assertEquals("UNVERIFIED", service.status(f, f, source, target, List.of(), Set.of("semgrep")));
    target.setRuleProfile(source.getRuleProfile());
    target.setProjectId(9L);
    assertEquals("UNVERIFIED", service.status(f, f, source, target, List.of(), Set.of("semgrep")));
  }

  @Test
  void humanMarkDoesNotClaimVerifiedAndHistoricalFixedIsNotNewlyResolved() {
    FindingView f = finding("old", "sql", "a.java");
    ScanRun source = run(1);
    assertEquals("TODO", service.status(f, f, source, null, List.of(), Set.of()));
    f.setTriageStatus("FIXED_PENDING");
    assertEquals("PENDING_VERIFICATION", service.status(f, f, source, null, List.of(), Set.of()));
    f.setResultStatus("FIXED");
    assertEquals(
        "BASELINE_FIXED", service.status(f, f, source, run(2), List.of(), Set.of("semgrep")));
  }

  @Test
  void semanticRuleChangesAreSentToReview() {
    FindingView f = finding("old", "cookie-missing-secure", "a.java");
    assertEquals(
        "NEEDS_REVIEW",
        service.status(
            f,
            f,
            run(1),
            run(2),
            List.of(finding("new", "cookie-missing-httponly", "a.java")),
            Set.of("semgrep")));
  }

  @Test
  void rejectsVerificationFromAnotherReport() {
    ScanRun target = run(2);
    target.setSourceReportRunId(9L);
    when(runs.findById(1L)).thenReturn(run(1));
    when(runs.findById(2L)).thenReturn(target);
    when(reports.archiveFindings(1L)).thenReturn(List.of());
    assertThrows(io.codesentry.common.error.BusinessException.class, () -> service.detail(1L, 2L));
  }

  @Test
  void usesArchivedMembersAndReturnsHistoryAndLiveNotes() {
    ScanRun target = run(2);
    target.setSourceReportRunId(1L);
    FindingView archived = finding("old", "sql", "a.java"), live = finding("old", "sql", "a.java");
    live.setTriageComment("修复提交 abc123");
    live.setTriageStatus("FIXED_PENDING");
    when(runs.findById(1L)).thenReturn(run(1));
    when(runs.findAcceptanceRuns(1L)).thenReturn(List.of(target));
    when(reports.archiveFindings(1L)).thenReturn(List.of(archived));
    when(findings.findByRunId(1L)).thenReturn(List.of(live));
    when(findings.findByRunId(2L)).thenReturn(List.of());
    ScanToolRun tool = new ScanToolRun();
    tool.setToolName("Semgrep");
    tool.setPhase("current");
    tool.setStatus("COMPLETED");
    when(tools.listByScanRun(2L)).thenReturn(List.of(tool));
    Map<String, Object> result = service.detail(1L, null);
    assertEquals(Map.of("NOT_DETECTED", 1L), result.get("counts"));
    Map<?, ?> row = (Map<?, ?>) ((List<?>) result.get("items")).get(0);
    assertEquals("修复提交 abc123", row.get("comment"));
    assertSame(archived, row.get("finding"));
  }
}
