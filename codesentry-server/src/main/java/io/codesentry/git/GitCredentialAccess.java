package io.codesentry.git;

import io.codesentry.ai.secret.AiToolSecretCipher;
import io.codesentry.common.error.BusinessException;
import io.codesentry.process.CommandExecutor;
import io.codesentry.process.CommandResult;
import io.codesentry.project.model.ScanProject;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.function.UnaryOperator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Credentials are encrypted at rest and passed through askpass environment, never through URLs or
 * argv.
 */
@Component
public class GitCredentialAccess {
  @org.springframework.beans.factory.annotation.Autowired
  private io.codesentry.git.credential.GitCredentialService sharedCredentials;

  private final AiToolSecretCipher cipher;

  public GitCredentialAccess(AiToolSecretCipher cipher) {
    this.cipher = cipher;
  }

  public String encrypt(String value) {
    return cipher.encrypt(value);
  }

  public CommandResult execute(
      CommandExecutor executor,
      Path directory,
      List<String> original,
      Duration timeout,
      ScanProject project) {
    if (project != null && project.getGitCredentialId() != null)
      project = sharedCredentials.resolve(project.getGitCredentialId(), project.getRemoteUrl());
    if (project == null || !project.isGitCredentialConfigured())
      return executeWithCredentials(executor, directory, original, timeout, null, null);
    if (!StringUtils.hasText(project.getGitUsername()))
      throw new BusinessException(HttpStatus.BAD_REQUEST, "Git 认证缺少账号，请编辑仓库重新填写");
    String password;
    try {
      password = cipher.decrypt(project.getGitPasswordCiphertext());
    } catch (IllegalStateException error) {
      throw new BusinessException(HttpStatus.CONFLICT, "Git 凭据无法解密，请编辑仓库重新填写密码或令牌");
    }
    return executeWithCredentials(
        executor, directory, original, timeout, project.getGitUsername(), password);
  }

  /**
   * Validation uses request credentials in memory without writing a credential or encryption key.
   */
  public CommandResult executeWithCredentials(
      CommandExecutor executor,
      Path directory,
      List<String> original,
      Duration timeout,
      String username,
      String password) {
    Map<String, String> environment = new HashMap<>();
    environment.put("GIT_TERMINAL_PROMPT", "0");
    environment.put("GCM_INTERACTIVE", "Never");
    if (!StringUtils.hasText(password))
      return executor.execute(directory, original, timeout, environment, UnaryOperator.identity());
    if (!StringUtils.hasText(username))
      throw new BusinessException(HttpStatus.BAD_REQUEST, "请填写 Git 账号");
    Path temporary = null;
    try {
      temporary =
          Files.createTempDirectory(
              "codesentry-git-askpass-",
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      Path askpass = temporary.resolve("askpass.sh");
      Files.writeString(
          askpass,
          "#!/bin/sh\ncase \"$1\" in\n  *sername*) printf '%s\\n' \"$CODESENTRY_GIT_USERNAME\" ;;\n  *assword*) printf '%s\\n' \"$CODESENTRY_GIT_PASSWORD\" ;;\n  *) exit 1 ;;\nesac\n",
          StandardCharsets.UTF_8);
      Files.setPosixFilePermissions(askpass, PosixFilePermissions.fromString("rwx------"));
      environment.put("GIT_ASKPASS", askpass.toAbsolutePath().toString());
      environment.put("LC_ALL", "C");
      environment.put("CODESENTRY_GIT_USERNAME", username);
      environment.put("CODESENTRY_GIT_PASSWORD", password);
      List<String> command = new ArrayList<>();
      command.add(original.get(0));
      // Do not fall back to unrelated stored accounts or forward credentials across redirects.
      command.addAll(List.of("-c", "credential.helper=", "-c", "http.followRedirects=false"));
      command.addAll(original.subList(1, original.size()));
      String basic =
          Base64.getEncoder()
              .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
      String encoded = java.net.URLEncoder.encode(password, StandardCharsets.UTF_8);
      UnaryOperator<String> redact =
          value ->
              value == null
                  ? null
                  : value
                      .replace(password, "[REDACTED]")
                      .replace(basic, "[REDACTED]")
                      .replace(encoded, "[REDACTED]");
      return executor.execute(directory, command, timeout, environment, redact);
    } catch (java.io.IOException e) {
      throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "无法准备 Git 认证，请检查临时目录权限");
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary.resolve("askpass.sh"));
          Files.deleteIfExists(temporary);
        } catch (java.io.IOException ignored) {
          /* The script contains no credentials. */
        }
      }
    }
  }
}
