package io.codesentry.project.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.codesentry.common.error.BusinessException;
import io.codesentry.git.GitRepositoryService;
import io.codesentry.project.mapper.ScanProjectMapper;
import io.codesentry.project.model.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class RepositorySyncServiceTest {
  final ScanProjectMapper mapper = mock(ScanProjectMapper.class);
  final GitRepositoryService git = mock(GitRepositoryService.class);
  final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
  final List<Runnable> queue = new ArrayList<>();
  final RepositorySyncService service;
  final ScanProject p = new ScanProject();

  RepositorySyncServiceTest() {
    when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    service = new RepositorySyncService(mapper, git, queue::add, manager);
    p.setId(1L);
    p.setEnabled(true);
    p.setSourceType(ProjectSourceType.GIT);
    p.setRemoteUrl("https://example.invalid/repo.git");
    p.setRepositoryRevision(2);
    p.setLastSyncStatus("PENDING");
    when(mapper.findByIdForUpdate(1L)).thenReturn(p);
    when(mapper.pendingRepositories()).thenReturn(List.of(1L));
    service.ready();
  }

  @Test
  void dispatchDoesNotDoNetworkAndDuplicateDispatchIsCoalesced() {
    when(git.cloneMirror(p.getRemoteUrl(), p)).thenReturn(Path.of("/cache/repo.git"));
    service.dispatch();
    service.dispatch();
    assertEquals(1, queue.size());
    verifyNoInteractions(git);
    queue.remove(0).run();
    assertEquals("COMPLETED", p.getLastSyncStatus());
    assertEquals("/cache/repo.git", p.getManagedPath());
    assertNotNull(p.getLastSyncedAt());
    assertFalse(service.isRunning(1L));
    verify(mapper).finishRepositorySync(p);
  }

  @Test
  void failedRefreshKeepsUsableCacheAndAllowsRetry() {
    p.setManagedPath("/cache/existing.git");
    doThrow(new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST, "认证失败"))
        .when(git)
        .refresh(p);
    service.dispatch();
    queue.remove(0).run();
    assertEquals("FAILED", p.getLastSyncStatus());
    assertEquals("/cache/existing.git", p.getManagedPath());
    assertFalse(service.isRunning(1L));
    p.setLastSyncStatus("PENDING");
    doNothing().when(git).refresh(p);
    service.dispatch();
    queue.remove(0).run();
    assertEquals("COMPLETED", p.getLastSyncStatus());
  }

  @Test
  void activeScanDefersBackgroundPullWithoutChangingQueueState() {
    when(mapper.countActiveTasks(1L)).thenReturn(1);
    service.dispatch();
    queue.remove(0).run();
    assertEquals("PENDING", p.getLastSyncStatus());
    verifyNoInteractions(git);
    assertFalse(service.isRunning(1L));
  }

  @Test
  void startupMarksInterruptedPullsAndPreservesPendingQueue() {
    verify(mapper).recoverRepositorySync();
    assertEquals("PENDING", p.getLastSyncStatus());
  }

  @Test
  void explicitPreScanRefreshRecordsFailureAndPropagatesIt() {
    p.setManagedPath("/cache/existing.git");
    doThrow(new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST, "网络失败"))
        .when(git)
        .refresh(p);
    assertThrows(BusinessException.class, () -> service.refreshForScan(p));
    assertEquals("FAILED", p.getLastSyncStatus());
    verify(mapper).finishRepositorySync(p);
  }
}
