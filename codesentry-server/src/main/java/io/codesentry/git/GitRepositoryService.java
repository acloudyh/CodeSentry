package io.codesentry.git;

import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.process.CommandExecutor;
import io.codesentry.process.CommandResult;
import io.codesentry.project.dto.BranchView;
import io.codesentry.project.model.ProjectSourceType;
import io.codesentry.project.model.ScanProject;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class GitRepositoryService {

  private final CommandExecutor commandExecutor;
  private final CodeSentryProperties properties;

  @org.springframework.beans.factory.annotation.Autowired
  private GitCredentialAccess credentialAccess;

  @org.springframework.beans.factory.annotation.Autowired private GitTlsTrust tlsTrust;

  public GitRepositoryService(CommandExecutor commandExecutor, CodeSentryProperties properties) {
    this.commandExecutor = commandExecutor;
    this.properties = properties;
  }

  public Path validateLocalRepository(String localPath) {
    if (!StringUtils.hasText(localPath)) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "本地仓库路径不能为空");
    }
    Path repository = Paths.get(localPath).toAbsolutePath().normalize();
    if (!Files.isDirectory(repository)) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "本地仓库目录不存在");
    }

    CommandResult result = git(repository, "rev-parse", "--is-inside-work-tree");
    if (!result.isSuccess() || !"true".equalsIgnoreCase(result.getOutput())) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "所选目录不是有效的 Git 工作仓库");
    }
    return repository;
  }

  public Path cloneMirror(String remoteUrl) {
    return cloneMirror(remoteUrl, null);
  }

  public Path cloneMirror(String remoteUrl, ScanProject project) {
    if (!StringUtils.hasText(remoteUrl)) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "Git 仓库地址不能为空");
    }
    validateRemoteUrl(remoteUrl.trim());
    Path repositoryRoot =
        properties
            .getStorage()
            .rootPath()
            .resolve(properties.getStorage().getRepositoriesDirectory());
    try {
      Files.createDirectories(repositoryRoot);
    } catch (IOException exception) {
      throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "无法创建仓库存储目录");
    }

    Path target = repositoryRoot.resolve(UUID.randomUUID() + ".git");
    boolean complete = false;
    try {
      CommandResult result =
          authenticated(
              null,
              Arrays.asList(
                  properties.getGit().getExecutable(),
                  "clone",
                  "--mirror",
                  remoteUrl.trim(),
                  target.toString()),
              project,
              remoteUrl);
      if (!result.isSuccess()) throw remoteFailure("克隆", result);
      complete = true;
    } finally {
      if (!complete) {
        try {
          if (Files.exists(target))
            try (java.util.stream.Stream<Path> paths = Files.walk(target)) {
              paths
                  .sorted(java.util.Comparator.reverseOrder())
                  .forEach(
                      path -> {
                        try {
                          Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                      });
            }
        } catch (IOException ignored) {
        }
      }
    }
    return target;
  }

  public void validateRemoteUrl(String remoteUrl) {
    if (remoteUrl.startsWith("file:") || remoteUrl.startsWith("/") || remoteUrl.startsWith("~")) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "远程仓库仅支持 SSH、HTTP 或 HTTPS 地址");
    }
    if (!remoteUrl.contains("://")) {
      if (!remoteUrl.matches("^[^@\\s]+@[^:\\s]+:.+$")) {
        throw new BusinessException(HttpStatus.BAD_REQUEST, "Git SSH 地址格式不正确");
      }
      return;
    }
    try {
      URI uri = new URI(remoteUrl);
      if (!("ssh".equalsIgnoreCase(uri.getScheme())
          || "http".equalsIgnoreCase(uri.getScheme())
          || "https".equalsIgnoreCase(uri.getScheme()))) {
        throw new BusinessException(HttpStatus.BAD_REQUEST, "远程仓库协议不受支持");
      }
      if (uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null)
        throw new BusinessException(HttpStatus.BAD_REQUEST, "请填写完整且不含查询参数的 Git 仓库地址");
      if (uri.getUserInfo() != null
          && (!"ssh".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo().contains(":"))) {
        throw new BusinessException(HttpStatus.BAD_REQUEST, "仓库地址不能内嵌账号或令牌，请在独立的认证输入框填写");
      }
    } catch (URISyntaxException exception) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "Git 仓库地址格式不正确");
    }
  }

  public void refresh(ScanProject project) {
    if (project.getSourceType() != ProjectSourceType.GIT) {
      return;
    }
    List<String> command =
        Arrays.asList(properties.getGit().getExecutable(), "remote", "update", "--prune");
    CommandResult result =
        authenticated(repositoryPath(project), command, project, project.getRemoteUrl());
    if (!result.isSuccess()) {
      throw remoteFailure("同步", result);
    }
  }

  private BusinessException remoteFailure(String operation, CommandResult result) {
    String output =
        result.getOutput() == null ? "" : result.getOutput().toLowerCase(java.util.Locale.ROOT);
    String detail =
        output.contains("certificate") || output.contains("ssl peer")
            ? "HTTPS 证书验证失败，请检查服务器证书与信任配置"
            : output.contains("authentication failed")
                    || output.contains("could not read username")
                    || output.contains("401")
                    || output.contains("403")
                    || output.contains("access denied")
                ? "认证失败，请检查账号、密码或访问令牌及仓库权限"
                : "请检查仓库地址、网络连接及访问凭据";
    return new BusinessException(HttpStatus.BAD_REQUEST, "Git 仓库" + operation + "失败：" + detail);
  }

  private CommandResult authenticated(
      Path directory, List<String> command, ScanProject project, String remoteUrl) {
    if (tlsTrust != null) command = tlsTrust.configure(command, remoteUrl);
    if (credentialAccess == null)
      return commandExecutor.execute(directory, command, Duration.ofSeconds(1800));
    return credentialAccess.execute(
        commandExecutor, directory, command, Duration.ofSeconds(1800), project);
  }

  public io.codesentry.project.dto.GitValidationResult validateConnection(
      String remoteUrl, String username, String password, ScanProject stored) {
    validateRemoteUrl(remoteUrl);
    List<String> command =
        List.of(
            properties.getGit().getExecutable(),
            "ls-remote",
            "--symref",
            remoteUrl,
            "HEAD",
            "refs/heads/*");
    if (tlsTrust != null) command = tlsTrust.configure(command, remoteUrl);
    Duration timeout =
        Duration.ofSeconds(Math.min(30, properties.getGit().getCommandTimeoutSeconds()));
    CommandResult result;
    try {
      if (credentialAccess == null) result = commandExecutor.execute(null, command, timeout);
      else if (StringUtils.hasText(password))
        result =
            credentialAccess.executeWithCredentials(
                commandExecutor, null, command, timeout, username, password);
      else result = credentialAccess.execute(commandExecutor, null, command, timeout, stored);
    } catch (BusinessException error) {
      if (error.getStatus() == HttpStatus.REQUEST_TIMEOUT)
        return io.codesentry.project.dto.GitValidationResult.failure(
            "TIMEOUT", "Git 连接超时，请检查地址、网络或代理配置");
      throw error;
    }
    if (!result.isSuccess()) return validationFailure(result);
    java.util.Set<String> branches = new java.util.TreeSet<>();
    String defaultBranch = null;
    for (String line : result.getOutput().split("\\R")) {
      if (line.startsWith("ref: refs/heads/") && line.endsWith("\tHEAD"))
        defaultBranch = line.substring("ref: refs/heads/".length(), line.indexOf('\t'));
      else if (line.matches("[0-9a-fA-F]{40,64}\\trefs/heads/.+"))
        branches.add(line.substring(line.indexOf("refs/heads/") + 11));
    }
    return new io.codesentry.project.dto.GitValidationResult(
        true,
        "SUCCESS",
        branches.isEmpty()
            ? "Git 连接和访问校验通过，仓库暂无分支"
            : "Git 连接和仓库读取校验通过，共发现 " + branches.size() + " 个分支",
        defaultBranch,
        new java.util.ArrayList<>(branches).subList(0, Math.min(200, branches.size())));
  }

  private io.codesentry.project.dto.GitValidationResult validationFailure(CommandResult result) {
    String text =
        result.getOutput() == null ? "" : result.getOutput().toLowerCase(java.util.Locale.ROOT);
    if (text.contains("certificate") || text.contains("ssl peer"))
      return io.codesentry.project.dto.GitValidationResult.failure(
          "CERTIFICATE_ERROR", "HTTPS 证书校验失败，请为该 Git 地址配置受信任证书；尚未完成账号认证");
    if (text.contains("host key verification failed"))
      return io.codesentry.project.dto.GitValidationResult.failure(
          "SSH_HOST_KEY_ERROR", "SSH 主机身份校验失败，请先配置该主机的受信任公钥");
    if (text.contains("authentication failed")
        || text.contains("could not read username")
        || text.contains("401")
        || text.contains("403")
        || text.contains("access denied")
        || text.contains("permission denied"))
      return io.codesentry.project.dto.GitValidationResult.failure(
          "AUTHENTICATION_ERROR", "Git 认证或授权失败，请检查账号、密码或访问令牌及仓库读取权限");
    if (text.contains("not found") || text.contains("does not appear to be a git repository"))
      return io.codesentry.project.dto.GitValidationResult.failure(
          "REPOSITORY_ACCESS_ERROR", "无法访问该仓库，请检查仓库地址及当前账号的读取权限");
    return io.codesentry.project.dto.GitValidationResult.failure(
        "CONNECTION_ERROR", "Git 连接失败，请检查地址、网络连通性及代理配置");
  }

  public List<BranchView> listBranches(ScanProject project) {
    Path repository = repositoryPath(project);
    CommandResult result =
        git(
            repository,
            "for-each-ref",
            "--format=%(refname:short)|%(objectname)",
            "refs/heads",
            "refs/remotes");
    if (!result.isSuccess()) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "读取 Git 分支失败");
    }

    Map<String, BranchView> branches = new LinkedHashMap<>();
    for (String line : result.getOutput().split("\\R")) {
      if (!StringUtils.hasText(line) || line.endsWith("/HEAD")) {
        continue;
      }
      String[] fields = line.split("\\|", 2);
      if (fields.length != 2) {
        continue;
      }
      boolean remote = fields[0].contains("/") && !fields[0].startsWith("refs/");
      String displayName = fields[0].startsWith("origin/") ? fields[0].substring(7) : fields[0];
      branches.putIfAbsent(displayName, new BranchView(displayName, fields[1], remote));
    }
    return new ArrayList<>(branches.values());
  }

  public String resolveCommit(ScanProject project, String ref) {
    if (!StringUtils.hasText(ref)) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "Git 分支不能为空");
    }
    Path repository = repositoryPath(project);
    for (String candidate : Arrays.asList(ref, "refs/heads/" + ref, "refs/remotes/origin/" + ref)) {
      CommandResult result = git(repository, "rev-parse", "--verify", candidate + "^{commit}");
      if (result.isSuccess() && result.getOutput().matches("[0-9a-fA-F]{40,64}")) {
        return result.getOutput();
      }
    }
    throw new BusinessException(HttpStatus.BAD_REQUEST, "无法解析分支或 Commit：" + ref);
  }

  public String mergeBase(ScanProject project, String baselineCommit, String scanCommit) {
    CommandResult result = git(repositoryPath(project), "merge-base", baselineCommit, scanCommit);
    if (!result.isSuccess() || !StringUtils.hasText(result.getOutput())) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "所选分支之间不存在可用的共同基线");
    }
    return result.getOutput();
  }

  public boolean isDirty(ScanProject project) {
    if (project.getSourceType() != ProjectSourceType.LOCAL) {
      return false;
    }
    return StringUtils.hasText(git(repositoryPath(project), "status", "--porcelain").getOutput());
  }

  public Path repositoryPath(ScanProject project) {
    String path =
        project.getSourceType() == ProjectSourceType.LOCAL
            ? project.getLocalPath()
            : project.getManagedPath();
    if (!StringUtils.hasText(path)) {
      throw new BusinessException(HttpStatus.CONFLICT, "项目尚未准备好本地仓库");
    }
    Path repository = Paths.get(path).toAbsolutePath().normalize();
    if (!Files.isDirectory(repository)) {
      throw new BusinessException(HttpStatus.CONFLICT, "项目对应的本地仓库目录不存在");
    }
    return repository;
  }

  public CommandResult git(Path repository, String... arguments) {
    List<String> command = new ArrayList<>();
    command.add(properties.getGit().getExecutable());
    command.addAll(Arrays.asList(arguments));
    return execute(repository, command);
  }

  private CommandResult execute(Path workingDirectory, List<String> command) {
    return commandExecutor.execute(
        workingDirectory,
        command,
        Duration.ofSeconds(properties.getGit().getCommandTimeoutSeconds()));
  }
}
