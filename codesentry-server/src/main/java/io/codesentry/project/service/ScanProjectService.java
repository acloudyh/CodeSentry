package io.codesentry.project.service;

import io.codesentry.common.error.BusinessException;
import io.codesentry.git.GitRepositoryService;
import io.codesentry.project.dto.BranchView;
import io.codesentry.project.dto.CreateProjectRequest;
import io.codesentry.project.mapper.ScanProjectMapper;
import io.codesentry.project.model.ProjectSourceType;
import io.codesentry.project.model.ScanProject;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ScanProjectService {

    private final ScanProjectMapper mapper;
    private final GitRepositoryService repositoryService;

    public ScanProjectService(ScanProjectMapper mapper, GitRepositoryService repositoryService) {
        this.mapper = mapper;
        this.repositoryService = repositoryService;
    }

    public List<ScanProject> findAll() {
        return mapper.findAll();
    }

    public ScanProject requireById(Long id) {
        ScanProject project = mapper.findById(id);
        if (project == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "项目不存在");
        }
        return project;
    }

    @Transactional
    public ScanProject create(CreateProjectRequest request) {
        ScanProject project = new ScanProject();
        project.setProjectName(request.getProjectName().trim());
        project.setTargetJavaVersion(request.getTargetJavaVersion());
        project.setSourceType(request.getSourceType());
        project.setDefaultBranch(StringUtils.hasText(request.getDefaultBranch())
                ? request.getDefaultBranch().trim() : "main");
        project.setEnabled(true);
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(project.getCreatedAt());

        if (request.getSourceType() == ProjectSourceType.LOCAL) {
            Path repository = repositoryService.validateLocalRepository(request.getLocalPath());
            project.setLocalPath(repository.toString());
        } else {
            Path repository = repositoryService.cloneMirror(request.getRemoteUrl());
            project.setRemoteUrl(request.getRemoteUrl().trim());
            project.setManagedPath(repository.toString());
        }

        mapper.insert(project);
        return project;
    }

    public List<BranchView> listBranches(Long id, boolean refresh) {
        ScanProject project = requireById(id);
        if (refresh) {
            repositoryService.refresh(project);
        }
        return repositoryService.listBranches(project);
    }

    @Transactional
    public ScanProject update(Long id, CreateProjectRequest request) {
        ScanProject project = requireById(id);
        if (!Boolean.TRUE.equals(project.getEnabled())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "已移除的仓库不能编辑");
        }
        if (mapper.countActiveTasks(id) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "该仓库有扫描或 AI 审核正在执行，请结束后再编辑");
        }
        if (project.getSourceType() != request.getSourceType()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "编辑时不能切换仓库来源，请另行登记");
        }
        if (project.getSourceType() == ProjectSourceType.LOCAL) {
            project.setLocalPath(repositoryService.validateLocalRepository(request.getLocalPath()).toString());
        } else {
            if (!StringUtils.hasText(request.getRemoteUrl())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "Git 仓库地址不能为空");
            }
            String remote = request.getRemoteUrl().trim();
            if (!remote.equals(project.getRemoteUrl())) {
                project.setManagedPath(repositoryService.cloneMirror(remote).toString());
                project.setRemoteUrl(remote);
            }
        }
        project.setProjectName(request.getProjectName().trim());
        project.setTargetJavaVersion(request.getTargetJavaVersion());
        project.setDefaultBranch(StringUtils.hasText(request.getDefaultBranch())
                ? request.getDefaultBranch().trim() : project.getDefaultBranch());
        repositoryService.resolveCommit(project, project.getDefaultBranch());
        project.setUpdatedAt(LocalDateTime.now());
        mapper.update(project);
        return project;
    }

    @Transactional
    public void delete(Long id) {
        requireById(id);
        mapper.disableById(id);
    }
}
