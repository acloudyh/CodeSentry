package io.codesentry.ai.secret;

import io.codesentry.config.CodeSentryProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** 只读兼容旧版 macOS 钥匙串，新的模型凭据由加密配置服务保存。 */
@Component
public class MacOsKeychainAiApiKeyStore implements AiApiKeyStore {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private final CodeSentryProperties properties;

  public MacOsKeychainAiApiKeyStore(CodeSentryProperties properties) {
    this.properties = properties;
  }

  @Override
  public Optional<String> load() {
    if (!supported()) return Optional.empty();
    Process process = null;
    try {
      process =
          new ProcessBuilder(
                  command("find-generic-password", "-a", account(), "-s", service(), "-w"))
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        return Optional.empty();
      }
      if (process.exitValue() != 0) return Optional.empty();
      String value =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      return value.isEmpty() ? Optional.empty() : Optional.of(value);
    } catch (Exception exception) {
      if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
      return Optional.empty();
    } finally {
      if (process != null) process.destroy();
    }
  }

  // 写入已统一使用 AiToolSecretCipher 加密存储；旧钥匙串仅读取迁移，禁止密钥进入进程参数。
  private boolean supported() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
        && Files.isExecutable(Path.of("/usr/bin/security"));
  }

  private List<String> command(String... arguments) {
    java.util.ArrayList<String> command = new java.util.ArrayList<>();
    command.add("/usr/bin/security");
    command.addAll(List.of(arguments));
    return command;
  }

  private String account() {
    String configured = properties.getAi().getKeychainAccount();
    return configured == null || configured.isBlank()
        ? System.getProperty("user.name")
        : configured;
  }

  private String service() {
    String configured = properties.getAi().getKeychainService();
    return configured == null || configured.isBlank() ? "CodeSentry-DeepSeek" : configured;
  }
}
