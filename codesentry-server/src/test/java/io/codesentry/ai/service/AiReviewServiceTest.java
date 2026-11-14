package io.codesentry.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.ai.dto.AiReviewDecision;
import io.codesentry.ai.dto.AiReviewEvidence;
import io.codesentry.ai.dto.AiReviewSummary;
import io.codesentry.ai.mapper.AiReviewMapper;
import io.codesentry.ai.model.AiReviewJob;
import io.codesentry.ai.model.AiReviewRecord;
import io.codesentry.ai.provider.AiReviewProvider;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.finding.model.FindingView;
import io.codesentry.finding.service.FindingPersistenceService;
import io.codesentry.scan.model.ScanRun;
import io.codesentry.scan.model.ScanRunStatus;
import io.codesentry.scan.service.ScanRunService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.SyncTaskExecutor;

class AiReviewServiceTest {
  @Test
  void policySummariesMatchCompleteReviewAndSkipPerFindingJobLookups() {
    AiReviewMapper mapper = mock(AiReviewMapper.class);
    CodeSentryProperties properties = new CodeSentryProperties();
    ObjectMapper json = new ObjectMapper();
    AiReviewService service =
        new AiReviewService(
            mapper,
            mock(FindingPersistenceService.class),
            mock(ScanRunService.class),
            new AiReviewEvidenceBuilder(properties, json),
            mock(AiReviewProvider.class),
            properties,
            json,
            new SyncTaskExecutor(),
            new SyncTaskExecutor());
    AiReviewRecord primary = new AiReviewRecord(),
        verification = new AiReviewRecord(),
        pending = new AiReviewRecord();
    for (AiReviewRecord record : List.of(primary, verification, pending)) {
      record.setFindingId(1L);
      record.setScanRunId(8L);
      record.setReviewJobId(9L);
      record.setConfidence(new java.math.BigDecimal("0.8"));
      record.setVerdict("CONFIRMED");
      record.setStatus("COMPLETED");
    }
    primary.setId(1L);
    primary.setReviewRound("PRIMARY");
    verification.setId(2L);
    verification.setReviewRound("VERIFY");
    pending.setId(3L);
    pending.setFindingId(2L);
    pending.setReviewRound("PRIMARY");
    when(mapper.findPolicyByRunId(8L)).thenReturn(List.of(verification, primary, pending));
    when(mapper.findByRunId(8L)).thenReturn(List.of(verification, primary));
    List<AiReviewSummary> policy = service.latestPolicyForRun(8L);
    assertEquals(
        service.latestForRun(8L).get(0).getConsolidatedVerdict(),
        policy.get(0).getConsolidatedVerdict());
    assertEquals("COMPLETED", policy.get(0).getStatus());
    assertEquals("FAILED", policy.get(1).getStatus());
    verify(mapper, never()).findJobById(any());
  }

  @Test
  void jobRecordsReturnsOnlyTheRequestedExecutionTrace() {
    AiReviewMapper mapper = mock(AiReviewMapper.class);
    FindingPersistenceService findings = mock(FindingPersistenceService.class);
    ScanRunService runs = mock(ScanRunService.class);
    AiReviewProvider provider = mock(AiReviewProvider.class);
    CodeSentryProperties properties = new CodeSentryProperties();
    ObjectMapper objectMapper = new ObjectMapper();
    AiReviewService service =
        new AiReviewService(
            mapper,
            findings,
            runs,
            new AiReviewEvidenceBuilder(properties, objectMapper),
            provider,
            properties,
            objectMapper,
            new SyncTaskExecutor(),
            new SyncTaskExecutor());
    AiReviewJob job = new AiReviewJob();
    job.setId(31L);
    AiReviewRecord record = new AiReviewRecord();
    record.setReviewJobId(31L);
    when(mapper.findJobById(31L)).thenReturn(job);
    when(mapper.findByJobId(31L)).thenReturn(List.of(record));

    List<AiReviewRecord> result = service.recordsForJob(31L);

    assertEquals(1, result.size());
    assertSame(record, result.get(0));
    verify(mapper).findByJobId(31L);
  }

  @Test
  void twoMatchingRoundsProduceSuggestionWithoutWritingManualTriage() {
    AiReviewMapper mapper = mock(AiReviewMapper.class);
    FindingPersistenceService findings = mock(FindingPersistenceService.class);
    ScanRunService runs = mock(ScanRunService.class);
    AiReviewProvider provider = mock(AiReviewProvider.class);
    CodeSentryProperties properties = new CodeSentryProperties();
    properties.getAi().setEnabled(true);
    properties.getAi().setBaseUrl("http://mock/v1");
    properties.getAi().setModel("mock-model");
    ObjectMapper objectMapper = new ObjectMapper();
    AiReviewEvidenceBuilder evidenceBuilder = new AiReviewEvidenceBuilder(properties, objectMapper);
    AiReviewService service =
        new AiReviewService(
            mapper,
            findings,
            runs,
            evidenceBuilder,
            provider,
            properties,
            objectMapper,
            new SyncTaskExecutor(),
            new SyncTaskExecutor());

    FindingView finding = new FindingView();
    finding.setId(9L);
    finding.setProjectId(3L);
    finding.setScanRunId(7L);
    finding.setScanner("Semgrep");
    finding.setRuleId("command-execution");
    finding.setSeverity("HIGH");
    finding.setTitle("命令执行");
    finding.setFilePath("src/JGitUtil.java");
    finding.setStartLine(198);
    finding.setCodeSnippet("executeCommand(input)");
    when(findings.requireLatest(9L)).thenReturn(finding);
    when(provider.providerName()).thenReturn("MOCK");
    when(provider.modelName()).thenReturn("mock-model");
    doAnswer(
            invocation -> {
              ((AiReviewJob) invocation.getArgument(0)).setId(21L);
              return 1;
            })
        .when(mapper)
        .insertJob(any(AiReviewJob.class));
    when(provider.review(any(), any(), any()))
        .thenReturn(decision("CONFIRMED", 0.94), decision("CONFIRMED", 0.91));

    AiReviewSummary summary = service.reviewFinding(9L);

    assertEquals("CONFIRMED", summary.getConsolidatedVerdict());
    assertTrue(summary.isHighConfidence());
    assertTrue(summary.isConfidenceAvailable());
    verify(mapper).finishJob(eq(21L), eq("COMPLETED"), isNull(), any());
    verify(findings, never()).triage(any(), any());
  }

  @Test
  void conflictingRoundsRequireHumanReview() {
    AiReviewMapper mapper = mock(AiReviewMapper.class);
    FindingPersistenceService findings = mock(FindingPersistenceService.class);
    ScanRunService runs = mock(ScanRunService.class);
    AiReviewProvider provider = mock(AiReviewProvider.class);
    CodeSentryProperties properties = new CodeSentryProperties();
    properties.getAi().setEnabled(true);
    properties.getAi().setBaseUrl("http://mock/v1");
    properties.getAi().setModel("mock-model");
    ObjectMapper objectMapper = new ObjectMapper();
    AiReviewEvidenceBuilder evidenceBuilder = new AiReviewEvidenceBuilder(properties, objectMapper);
    AiReviewService service =
        new AiReviewService(
            mapper,
            findings,
            runs,
            evidenceBuilder,
            provider,
            properties,
            objectMapper,
            new SyncTaskExecutor(),
            new SyncTaskExecutor());

    FindingView finding = new FindingView();
    finding.setId(10L);
    finding.setProjectId(3L);
    finding.setScanRunId(7L);
    finding.setScanner("Semgrep");
    finding.setRuleId("unsafe-innerhtml");
    finding.setSeverity("HIGH");
    finding.setTitle("XSS");
    finding.setFilePath("src/View.vue");
    finding.setStartLine(42);
    finding.setCodeSnippet("element.innerHTML = value");
    when(findings.requireLatest(10L)).thenReturn(finding);
    when(provider.providerName()).thenReturn("MOCK");
    when(provider.modelName()).thenReturn("mock-model");
    doAnswer(
            invocation -> {
              ((AiReviewJob) invocation.getArgument(0)).setId(22L);
              return 1;
            })
        .when(mapper)
        .insertJob(any(AiReviewJob.class));
    when(provider.review(any(), any(), any()))
        .thenReturn(decision("CONFIRMED", 0.93), decision("FALSE_POSITIVE", 0.88));

    AiReviewSummary summary = service.reviewFinding(10L);

    assertEquals("NEEDS_HUMAN", summary.getConsolidatedVerdict());
    assertEquals("NEEDS_HUMAN", summary.getStatus());
    assertTrue(summary.isDisagreement());
    assertFalse(summary.isHighConfidence());
    verify(findings, never()).triage(any(), any());
  }

  @Test
  void sameDependencyVersionUsesOneTwoRoundReviewAndPersistsResultForEveryCve() {
    AiReviewMapper mapper = mock(AiReviewMapper.class);
    FindingPersistenceService findings = mock(FindingPersistenceService.class);
    ScanRunService runs = mock(ScanRunService.class);
    AiReviewProvider provider = mock(AiReviewProvider.class);
    CodeSentryProperties properties = new CodeSentryProperties();
    properties.getAi().setEnabled(true);
    properties.getAi().setBaseUrl("http://mock/v1");
    properties.getAi().setModel("mock-model");
    ObjectMapper objectMapper = new ObjectMapper();
    AiReviewService service =
        new AiReviewService(
            mapper,
            findings,
            runs,
            new AiReviewEvidenceBuilder(properties, objectMapper),
            provider,
            properties,
            objectMapper,
            new SyncTaskExecutor(),
            new SyncTaskExecutor());
    ScanRun run = new ScanRun();
    run.setId(7L);
    run.setStatus(ScanRunStatus.COMPLETED);
    FindingView first = dependencyFinding(31L, "CVE-2017-5644", "Apache POI 远程攻击");
    FindingView second = dependencyFinding(32L, "CVE-2014-9527", "POI 拒绝服务");
    when(runs.requireById(7L)).thenReturn(run);
    when(findings.findByRunId(7L)).thenReturn(List.of(first, second));
    when(provider.providerName()).thenReturn("MOCK");
    when(provider.modelName()).thenReturn("mock-model");
    doAnswer(
            invocation -> {
              ((AiReviewJob) invocation.getArgument(0)).setId(23L);
              return 1;
            })
        .when(mapper)
        .insertJob(any(AiReviewJob.class));
    when(provider.review(any(), any(), any()))
        .thenReturn(decision("CONFIRMED", 0.94), decision("CONFIRMED", 0.91));

    AiReviewJob job = service.startRunReview(7L);

    assertEquals(1, job.getTotalFindings());
    ArgumentCaptor<AiReviewEvidence> evidence = ArgumentCaptor.forClass(AiReviewEvidence.class);
    verify(provider, times(2)).review(evidence.capture(), any(), any());
    assertTrue(evidence.getAllValues().get(0).getTitle().contains("POI 组件 2 个已知漏洞"));
    assertTrue(evidence.getAllValues().get(0).getDescription().contains("CVE-2017-5644"));
    assertTrue(evidence.getAllValues().get(0).getDescription().contains("CVE-2014-9527"));
    ArgumentCaptor<AiReviewRecord> records = ArgumentCaptor.forClass(AiReviewRecord.class);
    verify(mapper, times(4)).insertReview(records.capture());
    assertEquals(
        java.util.Set.of(31L, 32L),
        records.getAllValues().stream()
            .map(AiReviewRecord::getFindingId)
            .collect(java.util.stream.Collectors.toSet()));
  }

  private FindingView dependencyFinding(Long id, String ruleId, String title) {
    FindingView finding = new FindingView();
    finding.setId(id);
    finding.setProjectId(3L);
    finding.setScanRunId(7L);
    finding.setScanner("Trivy");
    finding.setCategory("VULNERABILITY");
    finding.setRuleId(ruleId);
    finding.setSeverity("MEDIUM");
    finding.setTitle(title);
    finding.setFilePath("src/main/webapp/WEB-INF/lib/poi-3.7.jar37");
    finding.setCodeSnippet("org.apache.poi:poi@3.7");
    return finding;
  }

  private AiReviewDecision decision(String verdict, double confidence) {
    AiReviewDecision decision = new AiReviewDecision();
    decision.setVerdict(verdict);
    decision.setConfidence(confidence);
    decision.setReason("代码证据支持");
    decision.setEvidenceLines(List.of(198));
    decision.setMissingEvidence(List.of());
    decision.setRecommendation("使用参数数组");
    return decision;
  }
}
