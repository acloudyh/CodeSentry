package io.codesentry.git;

import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.process.CommandExecutor;
import io.codesentry.process.CommandResult;
import io.codesentry.project.dto.BranchView;
import io.codesentry.project.model.ProjectSourceType;
import io.codesentry.project.model.ScanProject;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GitRepositoryService {

    private final CommandExecutor commandExecutor;
    private final CodeSentryProperties properties;

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
        if (!StringUtils.hasText(remoteUrl)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Git 仓库地址不能为空");
        }
        validateRemoteUrl(remoteUrl.trim());
        Path repositoryRoot = properties.getStorage().rootPath()
                .resolve(properties.getStorage().getRepositoriesDirectory());
        try {
            Files.createDirectories(repositoryRoot);
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "无法创建仓库存储目录");
        }

        Path target = repositoryRoot.resolve(UUID.randomUUID() + ".git");
        CommandResult result = execute(null, Arrays.asList(
                properties.getGit().getExecutable(), "clone", "--mirror", remoteUrl, target.toString()));
        if (!result.isSuccess()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Git 仓库克隆失败，请检查地址和本机 SSH 凭据");
        }
        return target;
    }

    private void validateRemoteUrl(String remoteUrl) {
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
            if (uri.getUserInfo() != null
                    && (!"ssh".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo().contains(":"))) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "仓库地址不能内嵌用户名或令牌，请使用 ssh-agent 或 Git 凭据助手");
            }
        } catch (URISyntaxException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Git 仓库地址格式不正确");
        }
    }

    public void refresh(ScanProject project) {
        if (project.getSourceType() != ProjectSourceType.GIT) {
            return;
        }
        CommandResult result = git(repositoryPath(project), "remote", "update", "--prune");
        if (!result.isSuccess()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "远程仓库更新失败，请检查网络和凭据");
        }
    }

    public List<BranchView> listBranches(ScanProject project) {
        Path repository = repositoryPath(project);
        CommandResult result = git(repository, "for-each-ref",
                "--format=%(refname:short)|%(objectname)", "refs/heads", "refs/remotes");
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
        String path = project.getSourceType() == ProjectSourceType.LOCAL
                ? project.getLocalPath() : project.getManagedPath();
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
        return commandExecutor.execute(workingDirectory, command,
                Duration.ofSeconds(properties.getGit().getCommandTimeoutSeconds()));
    }
}
