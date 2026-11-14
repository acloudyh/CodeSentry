package io.codesentry.ai.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.ai.dto.*;
import io.codesentry.ai.mapper.AiReviewMapper;
import io.codesentry.ai.model.*;
import io.codesentry.ai.provider.AiReviewProvider;
import io.codesentry.config.*;
import io.codesentry.finding.model.FindingView;
import io.codesentry.finding.service.FindingPersistenceService;
import io.codesentry.scan.model.*;
import io.codesentry.scan.service.ScanRunService;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class AiReviewParallelTest {
  static class Fixture implements AutoCloseable {
    final AiReviewMapper mapper = mock(AiReviewMapper.class);
    final AiReviewProvider provider = mock(AiReviewProvider.class),
        frozen = mock(AiReviewProvider.class);
    final FindingPersistenceService findings = mock(FindingPersistenceService.class);
    final ScanRunService scans = mock(ScanRunService.class);
    final Map<Long, String> states = new ConcurrentHashMap<>();
    final Map<Long, Long> latest = new ConcurrentHashMap<>();
    final Map<Long, List<FindingView>> inputs = new ConcurrentHashMap<>();
    final Map<Long, List<int[]>> progress = new ConcurrentHashMap<>();
    final Map<Long, CountDownLatch> finished = new ConcurrentHashMap<>();
    final List<AiReviewRecord> records = new CopyOnWriteArrayList<>();
    final AtomicLong sequence = new AtomicLong();
    final ThreadPoolTaskExecutor coordinators, workers;
    final AiReviewService service;

    Fixture() {
      CodeSentryProperties p = new CodeSentryProperties();
      p.getAi().setEnabled(true);
      p.getAi().setBaseUrl("http://mock/v1");
      p.getAi().setModel("original");
      coordinators = (ThreadPoolTaskExecutor) new AsyncConfig().aiReviewTaskExecutor(p);
      workers = (ThreadPoolTaskExecutor) new AsyncConfig().aiReviewWorkerExecutor(p);
      ObjectMapper json = new ObjectMapper();
      when(provider.snapshot()).thenReturn(frozen);
      when(frozen.providerName()).thenReturn("FROZEN");
      when(frozen.modelName()).thenReturn("original");
      when(findings.findByRunId(anyLong())).thenAnswer(c -> inputs.get(c.getArgument(0)));
      when(scans.requireById(anyLong()))
          .thenAnswer(
              c -> {
                ScanRun r = new ScanRun();
                r.setId(c.getArgument(0));
                r.setStatus(ScanRunStatus.COMPLETED);
                return r;
              });
      when(mapper.insertJob(any()))
          .thenAnswer(
              c -> {
                AiReviewJob j = c.getArgument(0);
                j.setId(sequence.incrementAndGet());
                states.put(j.getId(), "PENDING");
                latest.put(j.getScanRunId(), j.getId());
                return 1;
              });
      when(mapper.markJobRunning(anyLong(), any()))
          .thenAnswer(
              c -> {
                states.put(c.getArgument(0), "RUNNING");
                return 1;
              });
      when(mapper.findJobById(anyLong()))
          .thenAnswer(
              c -> {
                AiReviewJob j = new AiReviewJob();
                j.setId(c.getArgument(0));
                j.setStatus(states.get(j.getId()));
                return j;
              });
      when(mapper.findLatestJobByRunId(anyLong()))
          .thenAnswer(
              c -> {
                Long id = latest.get(c.getArgument(0));
                return id == null ? null : mapper.findJobById(id);
              });
      when(mapper.cancelJob(anyLong(), any()))
          .thenAnswer(
              c -> {
                states.put(c.getArgument(0), "CANCELLED");
                return 1;
              });
      when(mapper.updateJobProgress(anyLong(), anyInt(), anyInt()))
          .thenAnswer(
              c -> {
                progress
                    .computeIfAbsent(c.getArgument(0), x -> new CopyOnWriteArrayList<>())
                    .add(new int[] {c.getArgument(1), c.getArgument(2)});
                return 1;
              });
      when(mapper.finishJob(anyLong(), anyString(), any(), any()))
          .thenAnswer(
              c -> {
                Long id = c.getArgument(0);
                states.put(id, c.getArgument(1));
                finished.computeIfAbsent(id, x -> new CountDownLatch(1)).countDown();
                return 1;
              });
      when(mapper.insertReview(any()))
          .thenAnswer(
              c -> {
                AiReviewRecord record = c.getArgument(0);
                record.setId(sequence.incrementAndGet());
                records.add(record);
                return 1;
              });
      service =
          new AiReviewService(
              mapper,
              findings,
              scans,
              new AiReviewEvidenceBuilder(p, json),
              provider,
              p,
              json,
              coordinators,
              workers);
    }

    void input(long run, int count) {
      List<FindingView> list = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        FindingView f = new FindingView();
        f.setId(run * 100 + i);
        f.setScanRunId(run);
        f.setProjectId(run);
        f.setRuleId("rule-" + run + "-" + i);
        f.setScanner("Semgrep");
        f.setCodeSnippet("source-" + run + "-" + i);
        list.add(f);
      }
      inputs.put(run, list);
    }

    void done(Long id) throws Exception {
      assertTrue(
          finished.computeIfAbsent(id, x -> new CountDownLatch(1)).await(8, TimeUnit.SECONDS),
          "job did not finish");
    }

    @Override
    public void close() {
      coordinators.shutdown();
      workers.shutdown();
    }
  }

  static AiReviewDecision decision() {
    AiReviewDecision d = new AiReviewDecision();
    d.setVerdict("CONDITIONAL");
    d.setConfidence(.9);
    d.setReason("evidence");
    return d;
  }

  @Test
  void twoBatchesShareFourSlotsAndEveryGroupKeepsItsOwnOrderedRoundsAndProgress() throws Exception {
    try (Fixture f = new Fixture()) {
      f.input(1, 8);
      f.input(2, 8);
      CountDownLatch four = new CountDownLatch(4), release = new CountDownLatch(1);
      AtomicInteger active = new AtomicInteger(),
          peak = new AtomicInteger(),
          started = new AtomicInteger();
      Map<String, AiReviewDecision> primary = new ConcurrentHashMap<>();
      when(f.frozen.review(any(), anyString(), any()))
          .thenAnswer(
              c -> {
                int n = active.incrementAndGet();
                peak.accumulateAndGet(n, Math::max);
                try {
                  AiReviewEvidence e = c.getArgument(0);
                  String round = c.getArgument(1);
                  if ("PRIMARY".equals(round)) {
                    started.incrementAndGet();
                    four.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    AiReviewDecision d = decision();
                    primary.put(e.getRuleId(), d);
                    return d;
                  }
                  assertSame(primary.get(e.getRuleId()), c.getArgument(2));
                  return decision();
                } finally {
                  active.decrementAndGet();
                }
              });
      AiReviewJob a = f.service.startRunReview(1L), b = f.service.startRunReview(2L);
      try {
        assertTrue(four.await(5, TimeUnit.SECONDS));
        assertEquals(4, started.get());
        assertEquals(4, peak.get());
      } finally {
        release.countDown();
      }
      f.done(a.getId());
      f.done(b.getId());
      assertEquals(4, a.getConcurrency());
      assertEquals(4, peak.get());
      assertEquals("COMPLETED", f.states.get(a.getId()));
      assertEquals("COMPLETED", f.states.get(b.getId()));
      assertEquals(32, f.records.size());
      for (long run : List.of(1L, 2L))
        for (FindingView finding : f.inputs.get(run)) {
          List<AiReviewRecord> records = new ArrayList<>();
          for (AiReviewRecord record : f.records)
            if (finding.getId().equals(record.getFindingId())) records.add(record);
          assertEquals(2, records.size());
          assertEquals("PRIMARY", records.get(0).getReviewRound());
          assertEquals("VERIFY", records.get(1).getReviewRound());
          assertEquals("original", records.get(0).getModel());
          assertEquals("FROZEN", records.get(1).getProvider());
        }
      for (Long id : List.of(a.getId(), b.getId())) {
        int n = 0;
        for (int[] p : f.progress.get(id)) {
          assertTrue(p[0] >= n);
          assertTrue(p[0] + p[1] <= 8);
          n = p[0];
        }
        assertEquals(8, n);
      }
      verify(f.provider, never()).review(any(), anyString(), any());
    }
  }

  @Test
  void stoppingBatchInterruptsAllFourWorkersAndDoesNotPersistFailureOrStartRemainingGroups()
      throws Exception {
    try (Fixture f = new Fixture()) {
      f.input(1, 12);
      CountDownLatch started = new CountDownLatch(4), exited = new CountDownLatch(4);
      AtomicInteger calls = new AtomicInteger();
      when(f.frozen.review(any(), anyString(), any()))
          .thenAnswer(
              c -> {
                calls.incrementAndGet();
                started.countDown();
                try {
                  new CountDownLatch(1).await();
                  return decision();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException("stopped", e);
                } finally {
                  exited.countDown();
                }
              });
      AiReviewJob job = f.service.startRunReview(1L);
      assertTrue(started.await(5, TimeUnit.SECONDS));
      f.service.cancelJob(job.getId());
      assertTrue(exited.await(5, TimeUnit.SECONDS));
      assertEquals(4, calls.get());
      assertEquals("CANCELLED", f.states.get(job.getId()));
      assertTrue(f.records.isEmpty());
      verify(f.mapper, never()).finishJob(anyLong(), anyString(), any(), any());
    }
  }

  @Test
  void cancelledQueuedChildrenNeverCallModelAfterSlotsBecomeFree() throws Exception {
    try (Fixture f = new Fixture()) {
      f.input(1, 4);
      f.input(2, 4);
      CountDownLatch started = new CountDownLatch(4), release = new CountDownLatch(1);
      when(f.frozen.review(any(), anyString(), any()))
          .thenAnswer(
              c -> {
                AiReviewEvidence e = c.getArgument(0);
                assertTrue(e.getRuleId().startsWith("rule-1-"));
                if ("PRIMARY".equals(c.getArgument(1))) {
                  started.countDown();
                  assertTrue(release.await(5, TimeUnit.SECONDS));
                }
                return decision();
              });
      AiReviewJob a = f.service.startRunReview(1L);
      assertTrue(started.await(5, TimeUnit.SECONDS));
      AiReviewJob b = f.service.startRunReview(2L);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (f.workers.getThreadPoolExecutor().getQueue().size() < 4
          && System.nanoTime() < deadline) Thread.yield();
      assertEquals(4, f.workers.getThreadPoolExecutor().getQueue().size());
      f.service.cancelJob(b.getId());
      release.countDown();
      f.done(a.getId());
      assertEquals("CANCELLED", f.states.get(b.getId()));
      verify(f.frozen, times(8)).review(any(), anyString(), any());
    }
  }
}
