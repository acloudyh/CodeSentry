package io.codesentry.process;

import io.codesentry.common.error.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Component
public class CommandExecutor {

    public CommandResult execute(Path workingDirectory, List<String> command, Duration timeout) {
        return execute(workingDirectory, command, timeout, java.util.Collections.emptyMap(), java.util.function.UnaryOperator.identity());
    }

    public CommandResult execute(Path workingDirectory, List<String> command, Duration timeout,
                                 java.util.Map<String,String> environment, java.util.function.UnaryOperator<String> redact) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(environment);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        builder.redirectErrorStream(true);
        LocalDateTime startedAt = LocalDateTime.now();
        String workingDirectoryText = workingDirectory == null ? null : workingDirectory.toAbsolutePath().toString();
        Consumer<CommandTrace> listener = CommandTraceHolder.listener();

        Process runningProcess = null;
        try {
            CancellableTasks.check();
            Process process = builder.start();
            runningProcess = process;
            StringBuilder output = new StringBuilder();
            CompletableFuture<Void> outputFuture = CompletableFuture.runAsync(() ->
                    readOutput(process, command, workingDirectoryText, startedAt, listener, output, redact));

            long executionStarted = System.nanoTime();
            long deadline = executionStarted + timeout.toNanos();
            long lastHeartbeat = executionStarted;
            boolean completed = false;
            while (System.nanoTime() < deadline) {
                long remainingMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                if (process.waitFor(Math.min(1000, remainingMs), TimeUnit.MILLISECONDS)) {
                    completed = true; break;
                }
                CancellableTasks.check();
                long now = System.nanoTime();
                if (listener != null && now - lastHeartbeat >= TimeUnit.SECONDS.toNanos(10)) {
                    String captured;
                    synchronized (output) { captured = output.toString().trim(); }
                    long elapsed = TimeUnit.NANOSECONDS.toSeconds(now - executionStarted);
                    listener.accept(new CommandTrace(command, workingDirectoryText, null,
                            captured + "\n[CodeSentry] 命令仍在执行，已用时 " + elapsed + " 秒，上限 "
                                    + timeout.getSeconds() + " 秒。", startedAt, LocalDateTime.now()));
                    lastHeartbeat = now;
                }
            }
            if (!completed) {
                terminate(process);
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                outputFuture.join();
                CommandTraceHolder.set(new CommandTrace(command, workingDirectoryText, null,
                        output.toString().trim(), startedAt, LocalDateTime.now()));
                throw new BusinessException(HttpStatus.REQUEST_TIMEOUT, "本地命令执行超时");
            }

            outputFuture.join();
            String outputText = output.toString().trim();
            CommandTraceHolder.set(new CommandTrace(command, workingDirectoryText, process.exitValue(),
                    outputText, startedAt, LocalDateTime.now()));
            return new CommandResult(process.exitValue(), outputText);
        } catch (java.util.concurrent.CancellationException exception) {
            if (runningProcess != null) terminate(runningProcess);
            throw exception;
        } catch (IOException exception) {
            CommandTraceHolder.set(new CommandTrace(command, workingDirectoryText, null,
                    redact.apply(exception.getMessage()), startedAt, LocalDateTime.now()));
            throw new BusinessException(HttpStatus.BAD_REQUEST, "无法启动本地命令，请检查工具是否已安装");
        } catch (InterruptedException exception) {
            if (runningProcess != null) terminate(runningProcess);
            Thread.currentThread().interrupt();
            CommandTraceHolder.set(new CommandTrace(command, workingDirectoryText, null,
                    "本地命令执行被中断", startedAt, LocalDateTime.now()));
            throw new java.util.concurrent.CancellationException("本地命令已停止");
        }
    }

    private void terminate(Process process) {
        // Kill descendants before the parent so inherited output pipes do not remain open.
        process.descendants().forEach(child -> child.destroyForcibly());
        process.destroyForcibly();
    }

    private void readOutput(Process process,
                            List<String> command,
                            String workingDirectory,
                            LocalDateTime startedAt,
                            Consumer<CommandTrace> listener,
                            StringBuilder output, java.util.function.UnaryOperator<String> redact) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (output) {
                    output.append(redact.apply(line)).append('\n');
                    if (listener != null) {
                        listener.accept(new CommandTrace(command, workingDirectory, null,
                                output.toString().trim(), startedAt, LocalDateTime.now()));
                    }
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("读取命令输出失败", exception);
        }
    }
}
