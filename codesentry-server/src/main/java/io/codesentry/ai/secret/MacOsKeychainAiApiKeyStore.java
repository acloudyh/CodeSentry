package io.codesentry.ai.secret;

import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

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
            process = new ProcessBuilder(command("find-generic-password", "-a", account(), "-s", service(), "-w"))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() != 0) return Optional.empty();
            String value = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (process != null) process.destroy();
        }
    }

    @Override
    public void save(String apiKey) {
        if (!supported()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "当前系统不支持 macOS Keychain，请通过 CODESENTRY_AI_API_KEY 提供密钥");
        }
        Process process = null;
        try {
            process = new ProcessBuilder(command("add-generic-password", "-a", account(), "-s", service(),
                    "-w", apiKey, "-U"))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new BusinessException(HttpStatus.REQUEST_TIMEOUT, "保存 API Key 到 macOS Keychain 超时");
            }
            if (process.exitValue() != 0) {
                throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "无法保存 API Key 到 macOS Keychain");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(HttpStatus.REQUEST_TIMEOUT, "保存 API Key 已中断");
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "无法调用 macOS Keychain");
        } finally {
            if (process != null) process.destroy();
        }
    }

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
        return configured == null || configured.isBlank() ? System.getProperty("user.name") : configured;
    }

    private String service() {
        String configured = properties.getAi().getKeychainService();
        return configured == null || configured.isBlank() ? "CodeSentry-DeepSeek" : configured;
    }
}
