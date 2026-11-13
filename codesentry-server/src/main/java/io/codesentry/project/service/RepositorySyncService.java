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
    /** 报告专用实例不认领或恢复原执行实例的后台任务。 */
    @org.springframework.beans.factory.annotation.Value("${codesentry.runtime.worker-enabled:true}")
    private boolean workerEnabled = true;
    private final ScanProjectMapper mapper;
    private final GitRepositoryService git;
    private final TaskExecutor executor;
    private final TransactionTemplate tx;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private volatile boolean ready;

    /** 注入 Git 操作、独立任务池和短事务模板。 */
    public RepositorySyncService(ScanProjectMapper mapper, GitRepositoryService git,
            @Qualifier("repositoryTaskExecutor") TaskExecutor executor, PlatformTransactionManager manager) {
        this.mapper=mapper; this.git=git; this.executor=executor; this.tx=new TransactionTemplate(manager);
    }

    /** 启动后恢复被中断的拉取状态，再开放后台调度。 */
    @EventListener(ApplicationReadyEvent.class)
    public void ready() { if (!workerEnabled) return; mapper.recoverRepositorySync(); ready=true; }

    /** 查询当前实例是否正在处理指定资产的代码拉取。 */
    public boolean isRunning(Long id) { return running.contains(id); }

    /** 调度数据库中待拉取资产；线程池满时保留排队状态，避免任务丢失。 */
    @Scheduled(fixedDelay=2000)
    public void dispatch() {
        if (!ready) return;
        // 数据库 PENDING 是持久队列；线程池满时保留该状态，下一次调度继续尝试，保存接口无需等待 Git。
        for (Long id: mapper.pendingRepositories()) {
            if (!running.add(id)) continue;
            try { executor.execute(() -> { try { perform(id, false); } finally { running.remove(id); } }); }
            catch (org.springframework.core.task.TaskRejectedException rejected) { running.remove(id); }
        }
    }

    /** 扫描明确要求刷新时同步等待该次更新；与后台同资产任务互斥。 */
    public void refreshForScan(ScanProject project) {
        if (!running.add(project.getId())) throw new BusinessException(HttpStatus.CONFLICT,"代码正在后台拉取，请稍后重试扫描");
        try { perform(project.getId(), true); }
        finally { running.remove(project.getId()); }
    }

    /** 短事务认领资产后在事务外执行网络操作，最后按配置版本条件回写结果。 */
    private void perform(Long id, boolean forScan) {
        ScanProject snapshot=tx.execute(status -> {
            ScanProject p=mapper.findByIdForUpdate(id);
            if (p==null || !Boolean.TRUE.equals(p.getEnabled())) return null;
            if (!forScan && (!"PENDING".equals(p.getLastSyncStatus()) || mapper.countActiveTasks(id)>0)) return null;
            p.setLastSyncStatus("RUNNING"); p.setLastSyncMessage("正在后台拉取代码，仓库信息已保存");
            mapper.updateSyncState(p); return p;
        });
        if (snapshot==null) return;
        // 网络拉取放在事务外，避免长时间持有资产行锁，阻塞编辑或正常保存。
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
        // 仅相同配置版本可回写；用户修改地址/账号后，旧拉取结果不得覆盖新配置和排队状态。
        mapper.finishRepositorySync(snapshot);
        if (forScan && failure!=null) throw failure;
    }
}
