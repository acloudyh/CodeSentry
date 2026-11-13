package io.codesentry.scan.engine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SourceSnippetExtractor {

    static final int MAX_SNIPPET_CHARACTERS = 12000;
    private static final String TRUNCATION_MARKER = "\n... [代码片段过长，已保留首尾内容] ...\n";

    private SourceSnippetExtractor() {
    }

    /** 单次报告/查询内共享已验证的源码，限制缓存预算；请求结束即释放，不跨扫描或请求缓存。 */
    public static final class ReadSession {
        private final Map<Path, CachedFile> files = new LinkedHashMap<>();
        private long bytes;
        private List<String> get(Path root, Path path) {
            CachedFile file = files.get(path);
            return file != null && file.root.equals(root) ? file.lines : null;
        }
        private void put(Path root, Path path, List<String> lines, long size) {
            CachedFile previous = files.remove(path);
            if (previous != null) bytes -= previous.bytes;
            while (!files.isEmpty() && (files.size() >= 64 || bytes + size > 32L * 1024 * 1024)) {
                Path oldest = files.keySet().iterator().next(); bytes -= files.remove(oldest).bytes;
            }
            files.put(path, new CachedFile(root,lines,size)); bytes += size;
        }
    }
    private static final class CachedFile {
        final Path root; final List<String> lines; final long bytes;
        CachedFile(Path root,List<String> lines,long bytes) { this.root=root;this.lines=lines;this.bytes=bytes; }
    }

    public static String extract(Path allowedRoot, String filePath, Integer startLine, Integer endLine,
                                 String fallback) {
        return extract(allowedRoot,filePath,startLine,endLine,fallback,null);
    }

    /** 在同一读取会话复用源码，但首次读取仍校验允许目录、符号链接、文件大小及二进制内容。 */
    public static String extract(Path allowedRoot, String filePath, Integer startLine, Integer endLine,
                                 String fallback, ReadSession session) {
        if (allowedRoot == null || filePath == null || filePath.trim().isEmpty()
                || startLine == null || startLine <= 0) {
            return limit(fallback);
        }
        try {
            Path root = allowedRoot.toAbsolutePath().normalize();
            Path reportedPath = Path.of(filePath);
            Path sourcePath = (reportedPath.isAbsolute() ? reportedPath : root.resolve(reportedPath))
                    .toAbsolutePath().normalize();
            if (!sourcePath.startsWith(root)) return limit(fallback);
            List<String> lines = session == null ? null : session.get(root,sourcePath);
            if (lines == null) {
                if (!Files.isRegularFile(sourcePath)) return limit(fallback);
                Path real = sourcePath.toRealPath();
                long size = Files.size(real);
                if (!real.startsWith(root.toRealPath()) || size > 10 * 1024 * 1024) return limit(fallback);
                lines = Files.readAllLines(real, StandardCharsets.UTF_8);
                if (lines.stream().anyMatch(line -> line.indexOf('\0') >= 0)) return limit(fallback);
                if (session != null) session.put(root,sourcePath,lines,size);
            }
            if (lines.isEmpty() || startLine > lines.size()) {
                return limit(fallback);
            }
            int safeEndLine = Math.max(startLine,
                    Math.min(endLine == null ? startLine : endLine, lines.size()));
            int fromLine = Math.max(1, startLine - 2);
            int toLine = Math.min(lines.size(), safeEndLine + 2);
            StringBuilder snippet = new StringBuilder();
            for (int lineNumber = fromLine; lineNumber <= toLine; lineNumber++) {
                if (snippet.length() > 0) snippet.append('\n');
                snippet.append(lineNumber >= startLine && lineNumber <= safeEndLine ? "> " : "  ")
                        .append(String.format("%4d | ", lineNumber))
                        .append(lines.get(lineNumber - 1));
            }
            return limit(snippet.toString());
        } catch (Exception ignored) {
            return limit(fallback);
        }
    }

    static String limit(String value) {
        if (value == null || value.length() <= MAX_SNIPPET_CHARACTERS) return value;
        int remaining = MAX_SNIPPET_CHARACTERS - TRUNCATION_MARKER.length();
        int head = remaining / 2;
        int tail = remaining - head;
        return value.substring(0, head) + TRUNCATION_MARKER + value.substring(value.length() - tail);
    }
}
