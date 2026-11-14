package io.codesentry.scan.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.process.CommandExecutor;
import io.codesentry.process.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpStatus;

public abstract class AbstractCliScannerAdapter implements ScannerAdapter {

  protected final CommandExecutor commandExecutor;
  protected final ObjectMapper objectMapper;
  protected final CodeSentryProperties properties;

  protected AbstractCliScannerAdapter(
      CommandExecutor commandExecutor, ObjectMapper objectMapper, CodeSentryProperties properties) {
    this.commandExecutor = commandExecutor;
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  protected abstract String executable();

  @Override
  public boolean available() {
    try {
      return execute(null, List.of(executable(), "--version"), Duration.ofSeconds(10)).isSuccess();
    } catch (RuntimeException exception) {
      return false;
    }
  }

  protected CommandResult execute(Path workingDirectory, List<String> command, Duration timeout) {
    return commandExecutor.execute(workingDirectory, command, timeout);
  }

  protected JsonNode readJson(Path report) {
    if (!Files.isRegularFile(report)) {
      throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, name() + " 未生成预期的 JSON 报告");
    }
    try {
      return objectMapper.readTree(report.toFile());
    } catch (IOException exception) {
      throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, name() + " JSON 报告解析失败");
    }
  }

  protected String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  protected Integer integer(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asInt();
  }
}
