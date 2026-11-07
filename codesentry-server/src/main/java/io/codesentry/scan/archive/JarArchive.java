package io.codesentry.scan.archive;

import io.codesentry.common.error.BusinessException;
import io.codesentry.process.CancellableTasks;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads archives as data only; never loads or executes uploaded classes. */
public final class JarArchive {
    private static final long MAX_EXPANDED_BYTES = 8L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 50000;

    private JarArchive() { }

    public static void validate(Path archive) {
        inspect(archive, null);
    }

    /** Normal JARs are analyzed directly; Boot application classes use their own classpath root. */
    public static Layout prepare(Path archive, Path output) {
        return inspect(archive, output);
    }

    private static Layout inspect(Path archive, Path output) {
        List<Path> dependencies = new ArrayList<>();
        boolean boot = false;
        int classes = 0;
        int count = 0;
        long total = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                CancellableTasks.check();
                ZipEntry entry = entries.nextElement();
                if (++count > MAX_ENTRIES) throw invalid("JAR 条目数量超过限制");
                String name = entry.getName();
                Path relative = Path.of(name).normalize();
                if (name.contains("\\") || name.contains("/../") || name.contains("/./")
                        || relative.isAbsolute() || relative.startsWith("..")) {
                    throw invalid("JAR 包含非法归档路径");
                }
                if (entry.isDirectory()) continue;
                long size = entry.getSize();
                if (size < 0 || size > MAX_EXPANDED_BYTES - total) throw invalid("JAR 解压大小超过 8 GB 限制");
                total += size;
                if (name.endsWith(".class")) classes++;
                boolean appClass = name.startsWith("BOOT-INF/classes/") && name.endsWith(".class");
                boolean dependency = name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar");
                if (appClass) boot = true;
                if (output == null || !(appClass || dependency)) continue;
                Path target = output.resolve(relative).normalize();
                if (!target.startsWith(output.normalize())) throw invalid("JAR 包含非法归档路径");
                Files.createDirectories(target.getParent());
                try (InputStream input = zip.getInputStream(entry); OutputStream sink = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[65536];
                    long copied = 0;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        CancellableTasks.check();
                        copied += read;
                        if (copied > size) throw invalid("JAR 条目大小异常");
                        sink.write(buffer, 0, read);
                    }
                }
                if (dependency) dependencies.add(target);
            }
            if (classes == 0) throw invalid("JAR 中未发现可扫描的 Java 字节码");
            return new Layout(boot && output != null ? output.resolve("BOOT-INF/classes") : archive, dependencies);
        } catch (IOException | IllegalArgumentException exception) {
            throw invalid("无法读取 JAR，请上传完整、有效的 JAR 文件");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }

    public static final class Layout {
        private final Path target;
        private final List<Path> dependencies;
        Layout(Path target, List<Path> dependencies) { this.target = target; this.dependencies = dependencies; }
        public Path getTarget() { return target; }
        public List<Path> getDependencies() { return dependencies; }
    }
}
