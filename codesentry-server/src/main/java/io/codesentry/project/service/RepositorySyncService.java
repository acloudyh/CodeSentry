package io.codesentry.project.service;

import io.codesentry.common.error.BusinessException;
import io.codesentry.git.GitRepositoryService;
import io.codesentry.project.mapper.ScanProjectMapper;
import io.codesentry.project.model.ScanProject;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The persisted project state is a durable, coalescing queue: one outstanding pull per asset. */
@Service
public class RepositorySyncService {
    private final ScanProjectMapper mapper;
    private final GitRepositoryService git;
    private final TaskExecutor executor;
    private final TransactionTemplate tx;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private volatile boolean ready;

    public RepositorySyncService(ScanProjectMapper mapper, GitRepositoryService git,
            @Qualifier("repositoryTaskExecutor") TaskExecutor executor, PlatformTransactionManager manager) {
        this.mapper=mapper; this.git=git; this.executor=executor; this.tx=new TransactionTemplate(manager);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() { mapper.recoverRepositorySync(); ready=true; }

    public boolean isRunning(Long id) { return running.contains(id); }

    @Scheduled(fixedDelay=2000)
    public void dispatch() {
        if (!ready) return;
        for (Long id: mapper.pendingRepositories()) {
            if (!running.add(id)) continue;
            try { executor.execute(() -> { try { perform(id, false); } finally { running.remove(id); } }); }
            catch (org.springframework.core.task.TaskRejectedException rejected) { running.remove(id); }
        }
    }

    public void refreshForScan(ScanProject project) {
        if (!running.add(project.getId())) throw new BusinessException(HttpStatus.CONFLICT,"代码正在后台拉取，请稍后重试扫描");
        try { perform(project.getId(), true); }
        finally { running.remove(project.getId()); }
    }

    private void perform(Long id, boolean forScan) {
        ScanProject snapshot=tx.execute(status -> {
            ScanProject p=mapper.findByIdForUpdate(id);
            if (p==null || !Boolean.TRUE.equals(p.getEnabled())) return null;
            if (!forScan && (!"PENDING".equals(p.getLastSyncStatus()) || mapper.countActiveTasks(id)>0)) return null;
            p.setLastSyncStatus("RUNNING"); p.setLastSyncMessage("正在后台拉取代码，仓库信息已保存");
            mapper.updateSyncState(p); return p;
        });
        if (snapshot==null) return;
        RuntimeException failure=null;
        try {
            if (!StringUtils.hasText(snapshot.getManagedPath()))
                snapshot.setManagedPath(git.cloneMirror(snapshot.getRemoteUrl(), snapshot).toString());
            else git.refresh(snapshot);
            snapshot.setLastSyncedAt(LocalDateTime.now()); snapshot.setLastSyncStatus("COMPLETED");
            snapshot.setLastSyncMessage("代码已同步，扫描默认使用本地缓存；远端可能有后续更新");
        } catch (RuntimeException error) {
            failure=error; snapshot.setLastSyncStatus("FAILED");
            snapshot.setLastSyncMessage(error instanceof BusinessException ? error.getMessage() : "代码拉取失败，请重试或检查网络与仓库配置");
        }
        // Revision fencing prevents an old pull from overwriting a new URL, credentials or queue state.
        mapper.finishRepositorySync(snapshot);
        if (forScan && failure!=null) throw failure;
    }
}
