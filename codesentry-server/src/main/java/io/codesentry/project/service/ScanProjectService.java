package io.codesentry.project.service;

import io.codesentry.common.error.BusinessException;
import io.codesentry.git.GitRepositoryService;
import io.codesentry.project.dto.BranchView;
import io.codesentry.project.dto.CreateProjectRequest;
import io.codesentry.project.mapper.ScanProjectMapper;
import io.codesentry.project.model.ProjectSourceType;
import io.codesentry.project.model.ScanProject;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 管理资产元数据、凭据引用与本地缓存归属，远程拉取交由后台同步服务执行。 */
@Service
public class ScanProjectService {

  private final ScanProjectMapper mapper;
  private final GitRepositoryService repositoryService;

  @org.springframework.beans.factory.annotation.Autowired
  private io.codesentry.git.GitCredentialAccess credentials;

  public ScanProjectService(ScanProjectMapper mapper, GitRepositoryService repositoryService) {
    this.mapper = mapper;
    this.repositoryService = repositoryService;
  }

  @org.springframework.beans.factory.annotation.Autowired
  private RepositorySyncService repositorySync;

  @org.springframework.beans.factory.annotation.Autowired
  private io.codesentry.git.credential.GitCredentialService sharedCredentials;

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
    if (request.getSourceType() == ProjectSourceType.JAR_UPLOAD) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "请通过 JAR 上传入口创建扫描任务");
    }
    ScanProject project = new ScanProject();
    project.setProjectName(request.getProjectName().trim());
    applyRuntime(project, request);
    project.setSourceType(request.getSourceType());
    project.setDefaultBranch(
        StringUtils.hasText(request.getDefaultBranch())
            ? request.getDefaultBranch().trim()
            : "main");
    project.setEnabled(true);
    project.setCreatedAt(LocalDateTime.now());
    project.setUpdatedAt(project.getCreatedAt());

    if (request.getSourceType() == ProjectSourceType.LOCAL) {
      Path repository = repositoryService.validateLocalRepository(request.getLocalPath());
      project.setLocalPath(repository.toString());
    } else {
      if (!StringUtils.hasText(request.getRemoteUrl())) throw bad("Git 仓库地址不能为空");
      project.setRemoteUrl(request.getRemoteUrl().trim());
      applyCredentials(project, request, false);
      repositoryService.validateRemoteUrl(project.getRemoteUrl());
      queueSync(project);
    }

    mapper.insert(project);
    return project;
  }

  /** Probe only: does not register, clone, update metadata or persist submitted credentials. */
  public io.codesentry.project.dto.GitValidationResult validateGit(
      io.codesentry.project.dto.GitValidationRequest request) {
    String remote = request.getRemoteUrl().trim();
    repositoryService.validateRemoteUrl(remote);
    if (request.getGitCredentialId() != null) {
      if (StringUtils.hasText(request.getGitUsername())
          || StringUtils.hasText(request.getGitPassword())
          || request.isUseStoredCredentials()) throw bad("共享账号与独立账密不能同时提交");
      ScanProject selected = sharedCredentials.resolve(request.getGitCredentialId(), remote);
      return repositoryService.validateConnection(remote, null, null, selected);
    }
    String username =
        StringUtils.hasText(request.getGitUsername()) ? request.getGitUsername().trim() : null;
    String password = request.getGitPassword();
    ScanProject stored = null;
    if (StringUtils.hasText(password) && username == null) throw bad("请填写 Git 账号");
    if (StringUtils.hasText(password)
        && !(remote.toLowerCase(java.util.Locale.ROOT).startsWith("https://")
            || remote.toLowerCase(java.util.Locale.ROOT).startsWith("http://")))
      throw bad("账号密码认证仅支持 HTTP/HTTPS 仓库，SSH 请使用本机凭据");
    if (!StringUtils.hasText(password) && request.isUseStoredCredentials()) {
      if (request.getProjectId() == null) throw bad("请选择已登记仓库，或重新填写账号密码");
      stored = requireById(request.getProjectId());
      if (stored.getSourceType() != ProjectSourceType.GIT
          || !Boolean.TRUE.equals(stored.getEnabled())
          || !remote.equals(stored.getRemoteUrl())
          || !stored.isGitCredentialConfigured()
          || (username != null && !username.equals(stored.getGitUsername())))
        throw bad("仓库地址或账号已改变，请重新填写密码或令牌");
    } else if (!StringUtils.hasText(password) && username != null) throw bad("请填写 Git 密码或访问令牌");
    return repositoryService.validateConnection(remote, username, password, stored);
  }

  /** 仅读取本地缓存引用；远程更新必须通过显式 POST 同步入口提交。 */
  public List<BranchView> listBranches(Long id, boolean refresh) {
    ScanProject project = requireById(id);
    if (project.getSourceType() == ProjectSourceType.JAR_UPLOAD) return List.of();
    if (refresh) throw bad("请通过同步代码操作更新远程引用，分支查询不触发同步");
    if (project.getSourceType() == ProjectSourceType.GIT
        && !StringUtils.hasText(project.getManagedPath())) throw bad("代码正在后台拉取，完成后可选择分支");
    return repositoryService.listBranches(project);
  }

  @Transactional
  public ScanProject update(Long id, CreateProjectRequest request) {
    ScanProject project = requireForUpdate(id);
    if (project.getSourceType() == ProjectSourceType.JAR_UPLOAD) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "请通过 JAR 项目编辑入口修改信息或替换文件");
    }
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
      project.setLocalPath(
          repositoryService.validateLocalRepository(request.getLocalPath()).toString());
    } else {
      if (!StringUtils.hasText(request.getRemoteUrl())) {
        throw new BusinessException(HttpStatus.BAD_REQUEST, "Git 仓库地址不能为空");
      }
      String remote = request.getRemoteUrl().trim();
      boolean changed = !remote.equals(project.getRemoteUrl());
      project.setRemoteUrl(remote);
      repositoryService.validateRemoteUrl(remote);
      String previousCredential = project.getGitPasswordCiphertext();
      Long previousCredentialId = project.getGitCredentialId();
      applyCredentials(project, request, changed);
      if (changed) {
        project.setManagedPath(null);
        project.setLastSyncedAt(null);
      }
      if (changed
          || !java.util.Objects.equals(previousCredentialId, project.getGitCredentialId())
          || !java.util.Objects.equals(previousCredential, project.getGitPasswordCiphertext()))
        queueSync(project);
    }
    project.setProjectName(request.getProjectName().trim());
    applyRuntime(project, request);
    project.setDefaultBranch(
        StringUtils.hasText(request.getDefaultBranch())
            ? request.getDefaultBranch().trim()
            : project.getDefaultBranch());
    if (project.getSourceType() == ProjectSourceType.LOCAL)
      repositoryService.resolveCommit(project, project.getDefaultBranch());
    project.setUpdatedAt(LocalDateTime.now());
    mapper.update(project);
    return project;
  }

  private ScanProject requireForUpdate(Long id) {
    ScanProject project = mapper.findByIdForUpdate(id);
    if (project == null) throw new BusinessException(HttpStatus.NOT_FOUND, "扫描资产不存在");
    return project;
  }

  private void applyRuntime(ScanProject project, CreateProjectRequest request) {
    if (request.getApplicationType() != null)
      project.setApplicationType(request.getApplicationType());
    if (project.getApplicationType() == null)
      project.setApplicationType(io.codesentry.project.model.ApplicationType.BACKEND);
    project.setTargetJavaVersion(
        project.getApplicationType() == io.codesentry.project.model.ApplicationType.FRONTEND
            ? null
            : request.getTargetJavaVersion());
    if (project.getApplicationType() == io.codesentry.project.model.ApplicationType.BACKEND)
      project.setTargetNodeVersion(null);
    else if (request.getApplicationType() != null || request.getTargetNodeVersion() != null)
      project.setTargetNodeVersion(
          StringUtils.hasText(request.getTargetNodeVersion())
              ? request.getTargetNodeVersion().trim()
              : null);
  }

  /** 保存共享账号引用，或兼容原仓库的独立账密；切换方式时清理另一种认证。 */
  private void applyCredentials(
      ScanProject project, CreateProjectRequest request, boolean remoteChanged) {
    if (request.getGitCredentialId() != null) {
      if (request.getClearGitCredentials()
          || StringUtils.hasText(request.getGitUsername())
          || StringUtils.hasText(request.getGitPassword())) throw bad("共享账号与独立账密不能同时提交");
      sharedCredentials.checkSelection(request.getGitCredentialId(), project.getRemoteUrl());
      project.setGitCredentialId(request.getGitCredentialId());
      project.setGitUsername(null);
      project.setGitPasswordCiphertext(null);
      return;
    }
    if (project.getGitCredentialId() != null
        && !request.getClearGitCredentials()
        && !StringUtils.hasText(request.getGitUsername())
        && !StringUtils.hasText(request.getGitPassword())) {
      sharedCredentials.checkSelection(project.getGitCredentialId(), project.getRemoteUrl());
      return;
    }
    if (project.getGitCredentialId() != null) {
      project.setGitCredentialId(null);
      project.setGitUsername(null);
      project.setGitPasswordCiphertext(null);
    }
    if (request.getClearGitCredentials()) {
      project.setGitUsername(null);
      project.setGitPasswordCiphertext(null);
      return;
    }
    boolean hasPassword = StringUtils.hasText(request.getGitPassword());
    boolean hasUsername = StringUtils.hasText(request.getGitUsername());
    if (hasPassword && !hasUsername) throw bad("填写密码或令牌时，Git 账号不能为空");
    if (hasUsername
        && !(project.getRemoteUrl().toLowerCase(java.util.Locale.ROOT).startsWith("https://")
            || project.getRemoteUrl().toLowerCase(java.util.Locale.ROOT).startsWith("http://")))
      throw bad("账号密码认证仅支持 HTTP/HTTPS 仓库；SSH 地址请使用 ssh-agent");
    if (remoteChanged && !hasPassword) {
      project.setGitPasswordCiphertext(null);
      project.setGitUsername(null);
      if (hasUsername) throw bad("修改远程地址后请重新填写密码或访问令牌");
    }
    if (hasPassword) {
      project.setGitUsername(request.getGitUsername().trim());
      project.setGitPasswordCiphertext(credentials.encrypt(request.getGitPassword()));
    } else if (hasUsername) {
      if (!project.isGitCredentialConfigured()) throw bad("请填写 Git 密码或访问令牌");
      if (!request.getGitUsername().trim().equals(project.getGitUsername()))
        throw bad("更换 Git 账号时请同时填写密码或访问令牌");
    }
  }

  @Transactional(noRollbackFor = BusinessException.class)
  public ScanProject sync(Long id) {
    ScanProject project = requireForUpdate(id);
    if (!Boolean.TRUE.equals(project.getEnabled())
        || project.getSourceType() != ProjectSourceType.GIT) throw bad("仅已登记的远程 Git 仓库支持同步代码");
    if (mapper.countActiveTasks(id) > 0)
      throw new BusinessException(HttpStatus.CONFLICT, "仓库有扫描或工具审核正在执行，请结束后再同步代码");
    if (!"PENDING".equals(project.getLastSyncStatus())
        && !"RUNNING".equals(project.getLastSyncStatus())) {
      queueSync(project);
      mapper.update(project);
    }
    return project;
  }

  /** Explicit pre-scan refresh runs on the scan worker, never on the save request. */
  public void refreshForScan(ScanProject project) {
    repositorySync.refreshForScan(project);
  }

  public ScanProject requireReadyForScan(Long id) {
    ScanProject project = requireForUpdate(id);
    if (project.getSourceType() == ProjectSourceType.GIT
        && (!StringUtils.hasText(project.getManagedPath())
            || "RUNNING".equals(project.getLastSyncStatus())
            || repositorySync.isRunning(id)))
      throw new BusinessException(HttpStatus.CONFLICT, "代码尚未就绪或正在拉取，请等待拉取完成后扫描");
    return project;
  }

  private void queueSync(ScanProject project) {
    project.setRepositoryRevision(project.getRepositoryRevision() + 1);
    project.setLastSyncStatus("PENDING");
    project.setLastSyncMessage("已保存，等待后台拉取代码");
  }

  private BusinessException bad(String message) {
    return new BusinessException(HttpStatus.BAD_REQUEST, message);
  }

  @Transactional
  public void delete(Long id) {
    requireById(id);
    mapper.clearBusinessOwnership(id);
    mapper.disableById(id);
  }
}
