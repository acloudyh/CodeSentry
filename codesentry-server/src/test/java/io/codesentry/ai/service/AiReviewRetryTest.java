package io.codesentry.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.ai.dto.AiReviewDecision;
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
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiReviewRetryTest {
    private final AiReviewMapper mapper = mock(AiReviewMapper.class);
    private final AiReviewProvider provider = mock(AiReviewProvider.class);
    private final FindingPersistenceService findings = mock(FindingPersistenceService.class);
    private final ScanRunService scans = mock(ScanRunService.class);
    private final List<String> calls = new ArrayList<>();
    private final FindingView first = finding(1L), second = finding(2L);

    private AiReviewService service() {
        CodeSentryProperties properties = new CodeSentryProperties();
        properties.getAi().setEnabled(true);
        properties.getAi().setBaseUrl("http://mock/v1");
        properties.getAi().setModel("mock");
        ScanRun run = new ScanRun(); run.setId(1L); run.setStatus(ScanRunStatus.COMPLETED);
        when(scans.requireById(1L)).thenReturn(run);
        when(findings.findByRunId(1L)).thenReturn(List.of(first, second));
        doAnswer(call -> { ((AiReviewJob) call.getArgument(0)).setId(20L); return 1; })
                .when(mapper).insertJob(any());
        ObjectMapper json = new ObjectMapper();
        return new AiReviewService(mapper, findings, scans, new AiReviewEvidenceBuilder(properties, json),
                provider, properties, json, new SyncTaskExecutor(), new SyncTaskExecutor());
    }

    @Test void retriesOnlyFailuresAfterAllFirstPassFindingsAndClearsFailureCount() {
        AiReviewService service = service();
        AtomicLong attempt = new AtomicLong();
        when(provider.review(any(), anyString(), any())).thenAnswer(call -> {
            io.codesentry.ai.dto.AiReviewEvidence evidence = call.getArgument(0);
            calls.add(evidence.getCodeSnippet() + ":" + call.getArgument(1));
            if ("source1".equals(evidence.getCodeSnippet()) && attempt.getAndIncrement() == 0)
                throw new IllegalStateException("connection failed");
            return decision();
        });
        service.startRunReview(1L);
        assertEquals(List.of("source1:PRIMARY", "source2:PRIMARY", "source2:VERIFY", "source1:PRIMARY", "source1:VERIFY"), calls);
        verify(mapper).updateJobProgress(20L, 2, 0);
        verify(mapper).finishJob(eq(20L), eq("COMPLETED"), isNull(), any());
    }

    @Test void persistentFailuresAreRetriedOnlyOnceAndRemainFailures() {
        AiReviewService service = service();
        when(provider.review(any(), anyString(), any())).thenThrow(new IllegalStateException("connection failed"));
        service.startRunReview(1L);
        verify(provider, times(4)).review(any(), eq("PRIMARY"), isNull());
        verify(mapper).finishJob(eq(20L), eq("FAILED"), eq("2 个问题 AI 审核失败"), any());
    }

    @Test void manualRetryDoesNotCallSuccessfullyReviewedFindings() {
        AiReviewService service = service();
        when(mapper.findByRunId(1L)).thenReturn(List.of(record(2L, 5L, "ERROR"),
                record(1L, 4L, "VERIFY"), record(1L, 3L, "PRIMARY")));
        when(provider.review(any(), anyString(), any())).thenReturn(decision());
        AiReviewJob job = service.retryFailedRunReview(1L);
        assertEquals(1, job.getTotalFindings());
        verify(provider, times(2)).review(argThat(e -> "source2".equals(e.getCodeSnippet())), anyString(), any());
        verify(provider, never()).review(argThat(e -> "source1".equals(e.getCodeSnippet())), anyString(), any());
    }

    @Test void regularBatchRetriesFindingWhenLatestAttemptFailedEvenIfAnOlderAttemptCompleted() {
        AiReviewService service = service();
        when(mapper.findByRunId(1L)).thenReturn(List.of(record(1L, 6L, "ERROR"),
                record(1L, 5L, "VERIFY"), record(1L, 4L, "PRIMARY")));
        when(provider.review(any(), anyString(), any())).thenReturn(decision());
        AiReviewJob job = service.startRunReview(1L);
        assertEquals(2, job.getTotalFindings());
        verify(provider, times(2)).review(argThat(e -> "source1".equals(e.getCodeSnippet())), anyString(), any());
        verify(provider, times(2)).review(argThat(e -> "source2".equals(e.getCodeSnippet())), anyString(), any());
    }

    @Test void interruptedPersistedJobIsClosedAndUnreviewedFindingsCanRestart() {
        AiReviewService service = service();
        AiReviewJob interrupted = new AiReviewJob();
        interrupted.setId(19L); interrupted.setStatus("RUNNING");
        when(mapper.findLatestJobByRunId(1L)).thenReturn(interrupted);
        when(provider.review(any(), anyString(), any())).thenReturn(decision());
        AiReviewJob job = service.startRunReview(1L);
        assertEquals(2, job.getTotalFindings());
        verify(mapper).finishJob(eq(19L), eq("FAILED"), eq("服务中断，任务未完整执行"), any());
        verify(provider, times(4)).review(any(), anyString(), any());
    }

    @Test void successfulRetryIgnoresEarlierErrorInSameJob() {
        AiReviewService service = service();
        when(mapper.findByFindingId(1L)).thenReturn(List.of(record(1L, 4L, "VERIFY"),
                record(1L, 3L, "PRIMARY"), record(1L, 2L, "ERROR")));
        assertEquals("COMPLETED", service.latestForFinding(1L).getStatus());
    }

    @Test void newPrimaryCannotReuseEarlierVerificationAndLatestErrorWins() {
        AiReviewService service = service();
        when(mapper.findByFindingId(1L)).thenReturn(List.of(record(1L, 5L, "PRIMARY"),
                record(1L, 4L, "VERIFY"), record(1L, 3L, "PRIMARY")));
        assertEquals("FAILED", service.latestForFinding(1L).getStatus());
        when(mapper.findByFindingId(1L)).thenReturn(List.of(record(1L, 6L, "ERROR"),
                record(1L, 4L, "VERIFY"), record(1L, 3L, "PRIMARY")));
        assertEquals("FAILED", service.latestForFinding(1L).getStatus());
    }

    private FindingView finding(Long id) {
        FindingView finding = new FindingView(); finding.setId(id); finding.setProjectId(1L);
        finding.setScanRunId(1L); finding.setCodeSnippet("source" + id); return finding;
    }
    private AiReviewDecision decision() {
        AiReviewDecision decision = new AiReviewDecision(); decision.setVerdict("CONDITIONAL");
        decision.setConfidence(0.9); decision.setReason("条件风险"); return decision;
    }
    private AiReviewRecord record(Long findingId, Long id, String round) {
        AiReviewRecord record = new AiReviewRecord(); record.setFindingId(findingId); record.setId(id);
        record.setReviewJobId(10L); record.setScanRunId(1L); record.setReviewRound(round);
        record.setVerdict("CONDITIONAL"); record.setConfidence(new BigDecimal("0.9"));
        record.setStatus("ERROR".equals(round) ? "FAILED" : "COMPLETED");
        record.setErrorMessage("ERROR".equals(round) ? "connection failed" : null); return record;
    }
}
