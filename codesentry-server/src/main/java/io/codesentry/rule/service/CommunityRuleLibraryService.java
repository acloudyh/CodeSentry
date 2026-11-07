package io.codesentry.rule.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.process.CommandExecutor;
import io.codesentry.process.CommandResult;
import io.codesentry.rule.dto.RuleLibraryStatus;
import io.codesentry.scan.service.JarUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class CommunityRuleLibraryService implements AutoCloseable {
    private static final String SOURCE = "https://semgrep.dev/c/p/default";
    private static final long MAX_BYTES = 50L * 1024 * 1024;
    private final CodeSentryProperties properties;
    private final CommandExecutor commands;
    private final ObjectMapper json;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "codesentry-rule-update"); thread.setDaemon(true); return thread;
    });
    private volatile String state = "IDLE";
    private volatile String message = "更新时联网下载社区规则，扫描时读取本地快照。";

    public CommunityRuleLibraryService(CodeSentryProperties properties, CommandExecutor commands, ObjectMapper json) {
        this.properties = properties; this.commands = commands; this.json = json;
    }

    private Path root() { return properties.getStorage().rootPath().resolve("rules/community"); }
    private Path manifestPath() { return root().resolve("current.json"); }

    private JsonNode manifest() {
        try {
            JsonNode node = json.readTree(manifestPath().toFile());
            if (node == null || !node.isObject()) return null;
            String file = node.path("file").asText();
            if (!file.matches("community-[0-9a-f]{64}\\.yml") || !Files.isRegularFile(root().resolve(file))) return null;
            return node;
        } catch (IOException exception) { return null; }
    }

    public RuleLibraryStatus status() {
        JsonNode node = manifest();
        return new RuleLibraryStatus(state, node != null,
                node == null ? null : node.path("version").asText(),
                node == null ? null : node.path("updatedAt").asText(),
                node == null ? null : node.path("ruleCount").asInt(), message);
    }

    public Path requireSnapshot() {
        JsonNode node = manifest();
        if (node == null) throw new BusinessException(HttpStatus.BAD_REQUEST,
                "本地社区规则库尚未同步，请先在“扫描规则”页面点击“手动更新规则库”");
        return root().resolve(node.path("file").asText());
    }

    public synchronized RuleLibraryStatus requestUpdate() {
        if ("RUNNING".equals(state)) return status();
        state = "RUNNING"; message = "正在下载社区规则…";
        try { worker.execute(this::updateSnapshot); }
        catch (RuntimeException error) { state = "FAILED"; message = "更新任务未能启动，请稍后重试。"; }
        return status();
    }

    // Keep immutable snapshots so an update cannot change rules already selected by a scan.
    void updateSnapshot() {
        Path candidate = null, empty = null, report = null, nextManifest = null;
        try {
            Files.createDirectories(root());
            candidate = Files.createTempFile(root(), "download-", ".yml");
            CommandResult download = commands.execute(root(), List.of("curl", "--fail", "--silent", "--show-error",
                    "--location", "--connect-timeout", "15", "--max-time", "180", "--max-filesize", String.valueOf(MAX_BYTES),
                    "--output", candidate.toString(), SOURCE), Duration.ofSeconds(190));
            if (!download.isSuccess()) throw new IOException("社区规则下载失败，请检查网络或代理后重试。");
            if (Files.size(candidate) == 0 || Files.size(candidate) > MAX_BYTES) throw new IOException("下载的规则文件为空或超过大小限制。");
            LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false);
            Object document;
            try (InputStream input = Files.newInputStream(candidate)) {
                document = new Yaml(new SafeConstructor(options)).load(input);
            }
            if (!(document instanceof Map) || !(((Map<?, ?>) document).get("rules") instanceof List)) {
                throw new IOException("下载内容不是有效的 Semgrep 规则库。");
            }
            List<?> rules = (List<?>) ((Map<?, ?>) document).get("rules");
            if (rules.isEmpty()) throw new IOException("下载的规则库不包含规则。");
            message = "下载完成，正在校验与当前扫描器的兼容性…";
            empty = Files.createTempDirectory(root(), "validate-empty-");
            report = Files.createTempFile(root(), "validation-", ".json");
            CommandResult validation = commands.execute(root(), List.of(properties.getScanners().getSemgrep().getExecutable(),
                    "scan", "--config", candidate.toString(), "--metrics=off", "--disable-version-check", "--strict",
                    "--json", "--output", report.toString(), empty.toString()), Duration.ofMinutes(3));
            if (!validation.isSuccess()) throw new IOException("规则校验失败或与当前 Semgrep 版本不兼容，已保留原规则库。");
            JsonNode checked = json.readTree(report.toFile());
            if (checked == null || !checked.path("errors").isArray() || !checked.path("errors").isEmpty()) {
                throw new IOException("规则校验报告包含错误，已保留原规则库。");
            }
            String hash = JarUploadService.sha256(candidate);
            String file = "community-" + hash + ".yml";
            Path target = root().resolve(file);
            if (!Files.exists(target)) Files.move(candidate, target);
            else Files.delete(candidate);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("file", file); metadata.put("version", hash.substring(0, 12));
            metadata.put("updatedAt", LocalDateTime.now().toString()); metadata.put("ruleCount", rules.size()); metadata.put("source", SOURCE);
            nextManifest = Files.createTempFile(root(), "manifest-", ".json");
            json.writeValue(nextManifest.toFile(), metadata);
            try { Files.move(nextManifest, manifestPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(nextManifest, manifestPath(), StandardCopyOption.REPLACE_EXISTING); }
            message = "规则库更新成功，后续扫描将使用此本地版本。"; state = "SUCCEEDED";
        } catch (Exception error) {
            message = error instanceof IOException ? error.getMessage() : "规则下载或校验失败，已保留原规则库，请重试。";
            state = "FAILED";
        } finally {
            for (Path path : Arrays.asList(candidate, report, nextManifest, empty)) {
                if (path != null) try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            }
        }
    }

    @PreDestroy @Override public void close() { worker.shutdownNow(); }
}
