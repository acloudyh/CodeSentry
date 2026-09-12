package io.codesentry.scan.service;

import io.codesentry.common.error.BusinessException;
import io.codesentry.project.model.ProjectSourceType;
import io.codesentry.project.model.ScanProject;
import io.codesentry.project.service.ScanProjectService;
import io.codesentry.scan.dto.CreateScanRunRequest;
import io.codesentry.scan.dto.ScanRunDetail;
import io.codesentry.scan.mapper.ScanRunMapper;
import io.codesentry.scan.model.ScanMode;
import io.codesentry.scan.model.ScanRun;
import io.codesentry.scan.model.ScanRunStatus;
import io.codesentry.scan.model.WorkspaceMode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import io.codesentry.rule.service.RuleProfileService;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ScanRunService {

    private final ScanRunMapper mapper;
    private final ScanProjectService projectService;
    private final ScanOrchestrator orchestrator;
    private final ScanToolRunService toolRunService;
    private final TaskExecutor taskExecutor;
    private final RuleProfileService ruleProfileService;

    public ScanRunService(ScanRunMapper mapper,
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

    public ScanRun start(CreateScanRunRequest request) {
        ScanProject project = projectService.requireById(request.getProjectId());
        validate(project, request);

        ScanRun run = new ScanRun();
        run.setProjectId(project.getId());
        run.setScanMode(request.getScanMode());
        run.setWorkspaceMode(request.getWorkspaceMode());
        run.setScanRef(request.getScanRef());
        run.setBaselineRef(request.getBaselineRef());
        run.setRuleProfile(StringUtils.hasText(request.getRuleProfile())
                ? request.getRuleProfile() : RuleProfileService.COMMUNITY_GENERAL);
        run.setRepositoryDirty(false);
        run.setStatus(ScanRunStatus.PENDING);
        run.setTotalFindings(0);
        run.setNewFindings(0);
        run.setFixedFindings(0);
        run.setCreatedAt(LocalDateTime.now());
        mapper.insert(run);

        taskExecutor.execute(() -> orchestrator.execute(run.getId(), request.isRefreshRemote()));
        return run;
    }

    public ScanRun requireById(Long id) {
        ScanRun run = mapper.findById(id);
        if (run == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "扫描任务不存在");
        }
        return run;
    }

    public List<ScanRun> recent(int limit) {
        return mapper.findRecent(Math.min(Math.max(limit, 1), 200));
    }

    public ScanRunDetail detail(Long id) {
        ScanRun run = requireById(id);
        return new ScanRunDetail(run, toolRunService.listByScanRun(id));
    }

    private void validate(ScanProject project, CreateScanRunRequest request) {
        ruleProfileService.validate(StringUtils.hasText(request.getRuleProfile())
                ? request.getRuleProfile() : RuleProfileService.COMMUNITY_GENERAL);
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
