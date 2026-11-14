package io.codesentry.scan.service;

import io.codesentry.common.error.BusinessException;
import io.codesentry.project.model.ProjectSourceType;
import io.codesentry.project.model.ScanProject;
import io.codesentry.project.service.ScanProjectService;
import io.codesentry.rule.service.RuleProfileService;
import io.codesentry.scan.dto.CreateScanRunRequest;
import io.codesentry.scan.dto.ScanRunDetail;
import io.codesentry.scan.mapper.ScanRunMapper;
import io.codesentry.scan.model.ScanMode;
import io.codesentry.scan.model.ScanRun;
import io.codesentry.scan.model.ScanRunStatus;
import io.codesentry.scan.model.WorkspaceMode;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 管理单资产扫描的创建、执行、停止及历史查询，供项目轮次和整改验收复用。 */
@Service
public class ScanRunService {
  private final io.codesentry.process.CancellableTasks tasks =
      new io.codesentry.process.CancellableTasks();

  private final ScanRunMapper mapper;
  private final ScanProjectService projectService;
  private final ScanOrchestrator orchestrator;
  private final ScanToolRunService toolRunService;
  private final TaskExecutor taskExecutor;

  @org.springframework.beans.factory.annotation.Autowired
  private org.springframework.transaction.PlatformTransactionManager transactionManager;

  private final RuleProfileService ruleProfileService;

  /** 注入扫描存储、资产校验、执行编排和独立任务池。 */
  public ScanRunService(
      ScanRunMapper mapper,
      ScanProjectService projectService,
      ScanOrchestrator orchestrator,
      ScanToolRunService toolRunService,
      RuleProfileService ruleProfileService,
      @Qualifier("scanTaskExecutor") TaskExecutor taskExecutor) {
    this.mapper = mapper;
    this.projectService = projectService;
    this.orchestrator = orchestrator;
    this.toolRunService = toolRunService;
    this.ruleProfileService = ruleProfileService;
    this.taskExecutor = taskExecutor;
  }

  /** 校验扫描配置并持久化任务，在事务提交后投入执行，避免回滚任务产生外部副作用。 */
  @org.springframework.transaction.annotation.Transactional(noRollbackFor = BusinessException.class)
  public ScanRun start(CreateScanRunRequest request) {
    if (request.getNetworkMode() == null)
      throw new BusinessException(HttpStatus.BAD_REQUEST, "请选择本地扫描或联网扫描");
    ScanProject project = projectService.requireById(request.getProjectId());
    if (project.getSourceType() == ProjectSourceType.GIT)
      project = projectService.requireReadyForScan(project.getId());
    validate(project, request);

    ScanRun run = new ScanRun();
    run.setProjectId(project.getId());
    run.setNetworkMode(request.getNetworkMode());
    run.setRemediationStatus(
        request.getNetworkMode() == io.codesentry.scan.model.NetworkMode.ONLINE
            ? "PENDING"
            : "NOT_REQUESTED");
    if (request.getWorkspaceMode() == WorkspaceMode.JAR_UPLOAD)
      run.setArtifactPath(project.getLocalPath());
    run.setSourceReportRunId(request.getSourceReportRunId());
    run.setScanMode(request.getScanMode());
    run.setWorkspaceMode(request.getWorkspaceMode());
    run.setScanRef(request.getScanRef());
    run.setBaselineRef(request.getBaselineRef());
    run.setRuleProfile(
        StringUtils.hasText(request.getRuleProfile())
            ? request.getRuleProfile()
            : RuleProfileService.COMMUNITY_GENERAL);
    run.setRepositoryDirty(false);
    run.setStatus(ScanRunStatus.PENDING);
    run.setTotalFindings(0);
    run.setNewFindings(0);
    run.setFixedFindings(0);
    run.setCreatedAt(LocalDateTime.now());
    mapper.insert(run);

    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isSynchronizationActive()) {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .registerSynchronization(
              new org.springframework.transaction.support.TransactionSynchronization() {
                @Override
                public void afterCommit() {
                  org.springframework.transaction.support.TransactionTemplate tx =
                      new org.springframework.transaction.support.TransactionTemplate(
                          transactionManager);
                  tx.setPropagationBehavior(
                      org.springframework.transaction.TransactionDefinition
                          .PROPAGATION_REQUIRES_NEW);
                  tx.executeWithoutResult(status -> dispatch(run, request, true));
                }
              });
    } else dispatch(run, request, false);
    return run;
  }

  private void dispatch(ScanRun run, CreateScanRunRequest request, boolean afterCommit) {
    try {
      tasks.submit(
          run.getId(),
          taskExecutor,
          () -> orchestrator.execute(run.getId(), request.isRefreshRemote()));
    } catch (RuntimeException error) {
      run.setStatus(ScanRunStatus.FAILED);
      run.setErrorMessage("扫描任务队列已满");
      run.setFinishedAt(LocalDateTime.now());
      mapper.finish(run);
      if (!afterCommit)
        throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "扫描任务队列已满，请稍后重试");
    }
  }

  /** 停止活动扫描与相关子进程，保留已执行结果并标记不完整状态。 */
  public ScanRun cancel(Long id) {
    requireById(id);
    // A short completion grace handles the five-second UI polling race atomically.
    if (mapper.cancel(id, LocalDateTime.now()) == 0
        && requireById(id).getStatus() != ScanRunStatus.CANCELLED) {
      throw new BusinessException(HttpStatus.CONFLICT, "扫描已结束，无法停止，请刷新扫描记录");
    }
    tasks.cancel(id);
    toolRunService.cancelRunning(id);
    return requireById(id);
  }

  /** 读取原扫描记录，不存在时返回明确的资源错误。 */
  public ScanRun requireById(Long id) {
    ScanRun run = mapper.findById(id);
    if (run == null) {
      throw new BusinessException(HttpStatus.NOT_FOUND, "扫描任务不存在");
    }
    return run;
  }

  /** 兼容原扫描最近列表，不改变扫描 ID 或项目参数含义。 */
  public List<ScanRun> recent(int limit) {
    return mapper.findRecent(Math.min(Math.max(limit, 1), 200));
  }

  /** 按项目或资产范围返回旧客户端使用的最近可出具报告扫描。 */
  public List<ScanRun> recentForReports(int limit, Long businessProjectId, Long assetId) {
    return mapper.findReportRuns(Math.min(Math.max(limit, 1), 200), businessProjectId, assetId);
  }

  /** 统计当前项目/资产条件下全部可出具报告的扫描数量。 */
  public long countForReports(Long businessProjectId, Long assetId) {
    return mapper.countReportRuns(businessProjectId, assetId);
  }

  /** 读取报告页对应的扫描；offset 由报告分页服务统一计算。 */
  public List<ScanRun> pageForReports(int size, long offset, Long businessProjectId, Long assetId) {
    return mapper.findReportPage(size, offset, businessProjectId, assetId);
  }

  /** 汇总原扫描、扫描器任务及问题证据以展示执行详情。 */
  public ScanRunDetail detail(Long id) {
    ScanRun run = requireById(id);
    return new ScanRunDetail(run, toolRunService.listByScanRun(id));
  }

  /** 校验资产类型、扫描目标、规则集及验收来源，拒绝不兼容的扫描配置。 */
  private void validate(ScanProject project, CreateScanRunRequest request) {
    if (project.getSourceType() == ProjectSourceType.JAR_UPLOAD
        || request.getWorkspaceMode() == WorkspaceMode.JAR_UPLOAD) {
      if (project.getSourceType() != ProjectSourceType.JAR_UPLOAD
          || request.getWorkspaceMode() != WorkspaceMode.JAR_UPLOAD
          || request.getScanMode() != ScanMode.FULL
          || request.getSourceReportRunId() != null) {
        throw new BusinessException(HttpStatus.BAD_REQUEST, "JAR 项目仅支持独立全量扫描，请选择已有 JAR 项目发起扫描");
      }
      request.setScanRef(java.nio.file.Path.of(project.getLocalPath()).getFileName().toString());
      request.setBaselineRef(null);
      request.setRefreshRemote(false);
    }

    if (request.getSourceReportRunId() != null) {
      ScanRun source = requireById(request.getSourceReportRunId());
      if (!project.getId().equals(source.getProjectId())
          || request.getScanMode() != ScanMode.FULL
          || !java.util.Objects.equals(source.getRuleProfile(), request.getRuleProfile())
          || mapper.hasReportSnapshot(source.getId()) == 0) {
        throw new BusinessException(HttpStatus.BAD_REQUEST, "整改验收必须关联已归档报告，并使用原项目、原规则集进行全量复扫");
      }
    }
    if (request.getNetworkMode() == io.codesentry.scan.model.NetworkMode.LOCAL)
      request.setRefreshRemote(false);
    ruleProfileService.validate(
        StringUtils.hasText(request.getRuleProfile())
            ? request.getRuleProfile()
            : RuleProfileService.COMMUNITY_GENERAL);
    if (request.getNetworkMode() == io.codesentry.scan.model.NetworkMode.LOCAL
        && request.getWorkspaceMode() != WorkspaceMode.JAR_UPLOAD) {
      for (String config :
          ruleProfileService.semgrepConfigs(
              StringUtils.hasText(request.getRuleProfile())
                  ? request.getRuleProfile()
                  : RuleProfileService.COMMUNITY_GENERAL)) {
        if (config.startsWith("http:") || config.startsWith("https:") || config.startsWith("p/"))
          throw new BusinessException(HttpStatus.BAD_REQUEST, "所选规则集尚未缓存到本地，请先同步规则或选择支持离线的规则集");
      }
    }
    if (request.getWorkspaceMode() == WorkspaceMode.CURRENT
        && project.getSourceType() != ProjectSourceType.LOCAL) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "只有本地目录项目可以扫描当前工作区");
    }
    if (request.getWorkspaceMode() == WorkspaceMode.BRANCH_SNAPSHOT
        && !StringUtils.hasText(request.getScanRef())) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "指定分支扫描必须选择扫描分支");
    }
    if (request.getScanMode() == ScanMode.INCREMENTAL
        && !StringUtils.hasText(request.getBaselineRef())) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "增量扫描必须选择基线分支");
    }
  }
}
