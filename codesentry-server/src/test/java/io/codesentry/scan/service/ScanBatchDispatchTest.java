package io.codesentry.scan.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.codesentry.project.model.*;
import io.codesentry.project.service.ScanProjectService;
import io.codesentry.rule.service.RuleProfileService;
import io.codesentry.scan.dto.CreateScanRunRequest;
import io.codesentry.scan.mapper.ScanRunMapper;
import io.codesentry.scan.model.*;
import org.junit.jupiter.api.*;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

class ScanBatchDispatchTest {
  private final ScanRunMapper mapper = mock(ScanRunMapper.class);
  private final TaskExecutor executor = mock(TaskExecutor.class);
  private final ScanProjectService projects = mock(ScanProjectService.class);
  private final RuleProfileService rules = mock(RuleProfileService.class);
  private final ScanRunService service =
      new ScanRunService(
          mapper,
          projects,
          mock(ScanOrchestrator.class),
          mock(ScanToolRunService.class),
          rules,
          executor);

  @BeforeEach
  void setup() {
    ScanProject jar = new ScanProject();
    jar.setId(1L);
    jar.setSourceType(ProjectSourceType.JAR_UPLOAD);
    jar.setLocalPath("/tmp/version-1.jar");
    when(projects.requireById(1L)).thenReturn(jar);
    doAnswer(
            call -> {
              ((ScanRun) call.getArgument(0)).setId(8L);
              return 1;
            })
        .when(mapper)
        .insert(any());
    PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    ReflectionTestUtils.setField(service, "transactionManager", manager);
    TransactionSynchronizationManager.initSynchronization();
  }

  @AfterEach
  void cleanup() {
    TransactionSynchronizationManager.clearSynchronization();
  }

  private CreateScanRunRequest request() {
    CreateScanRunRequest r = new CreateScanRunRequest();
    r.setProjectId(1L);
    r.setNetworkMode(NetworkMode.LOCAL);
    r.setScanMode(ScanMode.FULL);
    r.setWorkspaceMode(WorkspaceMode.JAR_UPLOAD);
    return r;
  }

  @Test
  void workersOnlyLaunchAfterCommitAndArtifactVersionIsPinned() {
    ScanRun run = service.start(request());
    verifyNoInteractions(executor);
    assertEquals("/tmp/version-1.jar", run.getArtifactPath());
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
    verify(executor).execute(any());
  }

  @Test
  void rejectedDispatchRecordsFailureAfterCommitWithoutBreakingSiblingCallbacks() {
    doThrow(new TaskRejectedException("full")).when(executor).execute(any());
    ScanRun run = service.start(request());
    assertDoesNotThrow(
        () ->
            TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit));
    assertEquals(ScanRunStatus.FAILED, run.getStatus());
    verify(mapper).finish(run);
  }

  @Test
  void rolledBackBatchNeverLaunchesWorkers() {
    service.start(request());
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    verifyNoInteractions(executor);
  }
}
