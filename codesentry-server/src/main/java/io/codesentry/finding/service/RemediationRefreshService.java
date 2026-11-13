package io.codesentry.finding.service;

import io.codesentry.common.error.BusinessException;
import io.codesentry.scan.mapper.ScanRunMapper;
import io.codesentry.scan.model.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import java.util.Set;

/** Reuses an original completed scan; does not start scanners or change findings/reviews. */
@Service
public class RemediationRefreshService {
    /** 报告专用实例不认领或恢复原执行实例的后台任务。 */
    @org.springframework.beans.factory.annotation.Value("${codesentry.runtime.worker-enabled:true}")
    private boolean workerEnabled = true;
    private final ScanRunMapper runs;
    private final OnlineRemediationService online;
    private final TaskExecutor executor;
    private final TransactionTemplate failureTransaction;
    /** 注入原扫描、联网核实及独立执行器，并为排队失败准备独立提交事务。 */
    public RemediationRefreshService(ScanRunMapper runs,OnlineRemediationService online,@Qualifier("remediationTaskExecutor") TaskExecutor executor,org.springframework.transaction.PlatformTransactionManager manager) { this.runs=runs;this.online=online;this.executor=executor;this.failureTransaction=new TransactionTemplate(manager);this.failureTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW); }
    /** 服务启动时将未结束的历史补查标为可重试的失败状态。 */
    @EventListener(ApplicationReadyEvent.class) public void recover() { if (workerEnabled) runs.recoverRemediationRefresh(); }
    /** 校验原扫描模式和结束状态，原子认领补查；提交事务后才将任务投入后台。 */
    @Transactional public ScanRun start(Long id) {
        ScanRun run=runs.findById(id);
        if(run==null) throw new BusinessException(HttpStatus.NOT_FOUND,"扫描记录不存在");
        if(run.getNetworkMode()!=NetworkMode.ONLINE) throw new BusinessException(HttpStatus.BAD_REQUEST,"仅联网扫描支持补充联网核实");
        if(!Set.of(ScanRunStatus.COMPLETED,ScanRunStatus.PARTIAL).contains(run.getStatus())) throw new BusinessException(HttpStatus.CONFLICT,"请在扫描结束后补充联网核实");
        // 数据库条件更新认领任务，重复点击不会创建第二个后台作业。
        if(runs.claimRemediationRefresh(id)==0) return runs.findById(id);
        // 等事务提交后再入队，确保后台线程读到 PENDING，且回滚的请求不会触发外部查询。
        if(TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { dispatch(id); } });
        else dispatch(id);
        run.setRemediationStatus("PENDING");run.setRemediationMessage("等待后台补充联网核实");return run;
    }
    /** 提交独立后台核实作业；队列拒绝或执行中断时记录失败并保留原证据。 */
    private void dispatch(Long id) {
        try { executor.execute(() -> {
            try { ScanRun run=runs.findById(id); if(run!=null && "PENDING".equals(run.getRemediationStatus())) online.enrich(run); }
            catch(Exception e) { runs.failRemediationRefresh(id,"后台联网补查中断，请重试；原扫描和审核记录保留"); }
        }); } catch(RuntimeException full) { /* afterCommit 之后使用独立事务，保证队列拒绝的失败状态真正提交。 */ failureTransaction.executeWithoutResult(tx -> runs.failRemediationRefresh(id,"后台补查队列暂满，请稍后重试")); }
    }
}
