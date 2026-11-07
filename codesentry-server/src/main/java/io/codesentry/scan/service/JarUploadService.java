package io.codesentry.scan.service;

import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.project.mapper.ScanProjectMapper;
import io.codesentry.project.model.ProjectSourceType;
import io.codesentry.project.model.ScanProject;
import io.codesentry.scan.archive.JarArchive;
import io.codesentry.scan.dto.CreateScanRunRequest;
import io.codesentry.scan.model.ScanMode;
import io.codesentry.scan.model.ScanRun;
import io.codesentry.scan.model.WorkspaceMode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

@Service
public class JarUploadService {
    public static final long MAX_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024;
    private final CodeSentryProperties properties;
    private final ScanProjectMapper projects;
    private final ScanRunService scans;

    public JarUploadService(CodeSentryProperties properties, ScanProjectMapper projects, ScanRunService scans) {
        this.properties = properties;
        this.projects = projects;
        this.scans = scans;
    }

    /** Register once; starting a scan is a separate operation. */
    public ScanProject register(MultipartFile file, String projectName, Integer javaVersion) {
        validateMetadata(projectName, javaVersion, false);
        StoredJar stored = store(file);
        try {
            String name = stored.archive.getFileName().toString();
            String title = projectName == null || projectName.isBlank() ? name : projectName.trim();
            if (title.length() > 80) title = title.substring(0, 80);
            ScanProject project = new ScanProject();
            project.setProjectName(title + " · " + stored.workspace.getFileName());
            project.setSourceType(ProjectSourceType.JAR_UPLOAD);
            project.setManagedPath(stored.workspace.toString());
            project.setLocalPath(stored.archive.toString());
            project.setTargetJavaVersion(javaVersion);
            project.setEnabled(true);
            project.setCreatedAt(LocalDateTime.now());
            project.setUpdatedAt(project.getCreatedAt());
            projects.insert(project);
            return project;
        } catch (RuntimeException exception) {
            cleanup(stored.workspace);
            throw exception;
        }
    }

    /** Compatibility endpoint for clients that explicitly request upload-and-scan. */
    public ScanRun upload(MultipartFile file, String projectName, Integer javaVersion) {
        ScanProject project = register(file, projectName, javaVersion);
        try {
            CreateScanRunRequest request = new CreateScanRunRequest();
            request.setProjectId(project.getId());
            request.setScanMode(ScanMode.FULL);
            request.setWorkspaceMode(WorkspaceMode.JAR_UPLOAD);
            request.setScanRef(Path.of(project.getLocalPath()).getFileName().toString());
            request.setRefreshRemote(false);
            return scans.start(request);
        } catch (RuntimeException exception) {
            projects.disableById(project.getId());
            cleanup(Path.of(project.getManagedPath()));
            throw exception;
        }
    }

    public static void validateMetadata(String projectName, Integer javaVersion, boolean required) {
        if (required && (projectName == null || projectName.isBlank())) throw bad("项目名称不能为空");
        if (projectName != null && projectName.trim().length() > 80) throw bad("项目名称不能超过 80 字符");
        if (javaVersion != null && !java.util.List.of(8, 11, 17, 21, 25).contains(javaVersion)) {
            throw bad("请选择支持的业务 Java 版本");
        }
    }

    public StoredJar store(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (file.isEmpty() || file.getSize() > MAX_UPLOAD_BYTES) throw bad("请选择不超过 2 GB（2048 MB） 的 JAR 文件");
        if (name == null || name.length() > 200 || !name.toLowerCase(Locale.ROOT).endsWith(".jar")
                || name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isISOControl)) {
            throw bad("文件名必须是不含路径的 .jar 文件名（最多 200 字符）");
        }
        Path workspace = properties.getStorage().rootPath().resolve("uploads").resolve(UUID.randomUUID().toString());
        try {
            Files.createDirectories(workspace);
            Path archive = workspace.resolve(name);
            try (InputStream input = file.getInputStream(); var output = Files.newOutputStream(archive)) {
                byte[] buffer = new byte[65536];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_UPLOAD_BYTES) throw bad("JAR 文件超过 2 GB（2048 MB） 限制");
                    output.write(buffer, 0, count);
                }
            }
            JarArchive.validate(archive);
            Files.writeString(workspace.resolve(".sha256"), sha256(archive));
            return new StoredJar(workspace, archive);
        } catch (IOException exception) {
            cleanup(workspace);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "无法保存上传的 JAR 文件");
        } catch (RuntimeException exception) {
            cleanup(workspace);
            throw exception;
        }
    }

    public static final class StoredJar {
        private final Path workspace;
        private final Path archive;
        StoredJar(Path workspace, Path archive) { this.workspace = workspace; this.archive = archive; }
        public Path getWorkspace() { return workspace; }
        public Path getArchive() { return archive; }
    }

    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format("%02x", value & 0xff));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public void cleanup(Path root) {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) { /* A failed upload must not hide the original failure. */ }
    }

    private static BusinessException bad(String message) { return new BusinessException(HttpStatus.BAD_REQUEST, message); }
}
