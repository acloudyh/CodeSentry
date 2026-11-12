package io.codesentry.finding.service;
import io.codesentry.scan.mapper.ScanRunMapper;
import io.codesentry.scan.model.*;
import io.codesentry.common.error.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.core.task.TaskRejectedException;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class RemediationRefreshServiceTest {
    final ScanRunMapper runs=mock(ScanRunMapper.class);
    final OnlineRemediationService online=mock(OnlineRemediationService.class);
    final List<Runnable> queue=new ArrayList<>();
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final ScanRun run=new ScanRun();
    final RemediationRefreshService service=new RemediationRefreshService(runs,online,queue::add,manager);
    @BeforeEach void setup() {run.setId(1L);run.setStatus(ScanRunStatus.COMPLETED);run.setNetworkMode(NetworkMode.ONLINE);run.setRemediationStatus("PARTIAL");when(runs.findById(1L)).thenReturn(run);when(runs.claimRemediationRefresh(1L)).thenReturn(1);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());}
    @AfterEach void cleanup() {if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.clearSynchronization();}
    @Test void startsAfterCommitAndCoalescesRepeatedRequests() {
        TransactionSynchronizationManager.initSynchronization();assertSame(run,service.start(1L));assertEquals("PENDING",run.getRemediationStatus());assertTrue(queue.isEmpty());verifyNoInteractions(online);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);assertEquals(1,queue.size());when(runs.claimRemediationRefresh(1L)).thenReturn(0);service.start(1L);assertEquals(1,queue.size());queue.remove(0).run();verify(online).enrich(run);
    }
    @Test void rejectsLocalOrUnfinishedScans() {run.setNetworkMode(NetworkMode.LOCAL);assertThrows(BusinessException.class,()->service.start(1L));run.setNetworkMode(NetworkMode.ONLINE);run.setStatus(ScanRunStatus.RUNNING);assertThrows(BusinessException.class,()->service.start(1L));verify(runs,never()).claimRemediationRefresh(anyLong());}
    @Test void rejectedQueueIsSavedInANewTransaction() {
        RemediationRefreshService rejected=new RemediationRefreshService(runs,online,r->{throw new TaskRejectedException("full");},manager);
        TransactionSynchronizationManager.initSynchronization();rejected.start(1L);TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(runs).failRemediationRefresh(eq(1L),contains("队列"));verify(manager).commit(any());
    }
}
