package io.codesentry.scan.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.codesentry.common.error.BusinessException;
import io.codesentry.project.service.ScanProjectService;
import io.codesentry.rule.service.RuleProfileService;
import io.codesentry.scan.mapper.ScanRunMapper;
import io.codesentry.scan.model.*;
import org.junit.jupiter.api.Test;

class ScanRunCancellationTest {
  private final ScanRunMapper mapper = mock(ScanRunMapper.class);
  private final ScanToolRunService tools = mock(ScanToolRunService.class);
  private final ScanRunService service =
      new ScanRunService(
          mapper,
          mock(ScanProjectService.class),
          mock(ScanOrchestrator.class),
          tools,
          mock(RuleProfileService.class),
          Runnable::run);

  @Test
  void acceptedStopReturnsCancelledAndCancelsRunningTools() {
    ScanRun run = new ScanRun();
    run.setId(1L);
    run.setStatus(ScanRunStatus.RUNNING);
    when(mapper.findById(1L)).thenReturn(run);
    when(mapper.cancel(eq(1L), any()))
        .thenAnswer(
            call -> {
              run.setStatus(ScanRunStatus.CANCELLED);
              return 1;
            });
    assertEquals(ScanRunStatus.CANCELLED, service.cancel(1L).getStatus());
    verify(tools).cancelRunning(1L);
  }

  @Test
  void nearCompletionStopStillReturnsCancelledWhenAtomicUpdateAcceptsIt() {
    ScanRun run = new ScanRun();
    run.setStatus(ScanRunStatus.COMPLETED);
    when(mapper.findById(1L)).thenReturn(run);
    when(mapper.cancel(eq(1L), any()))
        .thenAnswer(
            call -> {
              run.setStatus(ScanRunStatus.CANCELLED);
              return 1;
            });
    assertEquals(ScanRunStatus.CANCELLED, service.cancel(1L).getStatus());
  }

  @Test
  void stoppingOldFinishedRunDoesNotSilentlyReturnCompletedAsSuccess() {
    ScanRun run = new ScanRun();
    run.setStatus(ScanRunStatus.COMPLETED);
    when(mapper.findById(1L)).thenReturn(run);
    assertThrows(BusinessException.class, () -> service.cancel(1L));
    verifyNoInteractions(tools);
  }

  @Test
  void stoppedRunIsIdempotentAndFinishSqlCannotOverwriteCancelled() throws Exception {
    ScanRun run = new ScanRun();
    run.setStatus(ScanRunStatus.CANCELLED);
    when(mapper.findById(1L)).thenReturn(run);
    assertEquals(ScanRunStatus.CANCELLED, service.cancel(1L).getStatus());
    String sql =
        String.join(
            " ",
            ScanRunMapper.class
                .getMethod("finish", ScanRun.class)
                .getAnnotation(org.apache.ibatis.annotations.Update.class)
                .value());
    assertTrue(sql.contains("AND status IN ('PENDING','PREPARING','RUNNING')"));
  }
}
