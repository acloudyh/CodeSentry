package io.codesentry.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.ai.mapper.AiReviewMapper;
import io.codesentry.ai.model.AiReviewJob;
import io.codesentry.ai.provider.AiReviewProvider;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.finding.model.FindingView;
import io.codesentry.finding.service.FindingPersistenceService;
import io.codesentry.scan.model.ScanRun;
import io.codesentry.scan.model.ScanRunStatus;
import io.codesentry.scan.service.ScanRunService;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiReviewCancellationTest {
    private final AiReviewMapper mapper = mock(AiReviewMapper.class);
    private final AiReviewProvider provider = mock(AiReviewProvider.class);
    private final AtomicReference<AiReviewJob> stored = new AtomicReference<>();

    private AiReviewService service(TaskExecutor executor) {
        CodeSentryProperties properties = new CodeSentryProperties();
        properties.getAi().setEnabled(true);
        properties.getAi().setBaseUrl("http://mock/v1");
        properties.getAi().setModel("mock");
        FindingPersistenceService findings = mock(FindingPersistenceService.class);
        ScanRunService scans = mock(ScanRunService.class);
        ScanRun run = new ScanRun(); run.setStatus(ScanRunStatus.COMPLETED);
        when(scans.requireById(1L)).thenReturn(run);
        FindingView finding = new FindingView();
        finding.setId(1L); finding.setScanRunId(1L); finding.setProjectId(1L);
        finding.setCodeSnippet("source");
        when(findings.findByRunId(1L)).thenReturn(List.of(finding));
        doAnswer(call -> {
            AiReviewJob job = call.getArgument(0); job.setId(20L); stored.set(job); return 1;
        }).when(mapper).insertJob(any());
        when(mapper.findJobById(20L)).thenAnswer(call -> stored.get());
        when(mapper.cancelJob(eq(20L), any())).thenAnswer(call -> {
            stored.get().setStatus("CANCELLED"); return 1;
        });
        ObjectMapper json = new ObjectMapper();
        return new AiReviewService(mapper, findings, scans, new AiReviewEvidenceBuilder(properties, json),
                provider, properties, json, executor);
    }

    @Test void cancellingQueuedReviewDoesNotCallModel() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AiReviewService service = service(queued::set);
        service.startRunReview(1L);
        assertEquals("CANCELLED", service.cancelJob(20L).getStatus());
        queued.get().run();
        verify(provider, never()).review(any(), anyString(), any());
        verify(mapper, never()).insertReview(any());
    }

    @Test void cancellingRunningReviewInterruptsModelAndDoesNotCreateFakeFailure() throws Exception {
        CountDownLatch started = new CountDownLatch(1), exited = new CountDownLatch(1);
        AiReviewService service = service(task -> new Thread(() -> {
            try { task.run(); } finally { exited.countDown(); }
        }).start());
        when(provider.review(any(), anyString(), any())).thenAnswer(call -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted", error);
            }
            return null;
        });
        service.startRunReview(1L);
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            service.cancelJob(20L);
            assertTrue(exited.await(5, TimeUnit.SECONDS));
            assertEquals("CANCELLED", stored.get().getStatus());
            verify(mapper, never()).insertReview(any());
            verify(mapper, never()).finishJob(anyLong(), anyString(), any(), any(LocalDateTime.class));
            verify(provider, times(1)).review(any(), eq("PRIMARY"), isNull());
        } finally { service.cancelJob(20L); }
    }
}
