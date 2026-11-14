package io.codesentry.process;

import java.time.LocalDateTime;
import java.util.List;
import java.util.StringJoiner;

/** 面向工具运行明细的安全诊断快照，命令参数和日志在构造时统一脱敏。 */
public class CommandTrace {

  private final String commandLine;
  private final String workingDirectory;
  private final Integer exitCode;
  private final String output;
  private final LocalDateTime startedAt;
  private final LocalDateTime finishedAt;

  public CommandTrace(
      List<String> command,
      String workingDirectory,
      Integer exitCode,
      String output,
      LocalDateTime startedAt,
      LocalDateTime finishedAt) {
    this.commandLine = display(DiagnosticRedactor.command(command));
    this.workingDirectory = workingDirectory;
    this.exitCode = exitCode;
    this.output = DiagnosticRedactor.text(output);
    this.startedAt = startedAt;
    this.finishedAt = finishedAt;
  }

  public String getCommandLine() {
    return commandLine;
  }

  public String getWorkingDirectory() {
    return workingDirectory;
  }

  public Integer getExitCode() {
    return exitCode;
  }

  public String getOutput() {
    return output;
  }

  public LocalDateTime getStartedAt() {
    return startedAt;
  }

  public LocalDateTime getFinishedAt() {
    return finishedAt;
  }

  private static String display(List<String> command) {
    StringJoiner joiner = new StringJoiner(" ");
    for (String item : command) {
      joiner.add(quote(item));
    }
    return joiner.toString();
  }

  private static String quote(String value) {
    if (value == null || value.isEmpty()) {
      return "''";
    }
    if (value.matches("[A-Za-z0-9_./:=,@%+-]+")) {
      return value;
    }
    return "'" + value.replace("'", "'\\''") + "'";
  }
}
