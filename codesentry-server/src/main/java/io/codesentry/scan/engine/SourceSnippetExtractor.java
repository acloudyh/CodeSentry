package io.codesentry.scan.engine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class SourceSnippetExtractor {

    static final int MAX_SNIPPET_CHARACTERS = 12000;
    private static final String TRUNCATION_MARKER = "\n... [代码片段过长，已保留首尾内容] ...\n";

    private SourceSnippetExtractor() {
    }

    public static String extract(Path allowedRoot, String filePath, Integer startLine, Integer endLine,
                                 String fallback) {
        if (allowedRoot == null || filePath == null || filePath.trim().isEmpty()
                || startLine == null || startLine <= 0) {
            return limit(fallback);
        }
        try {
            Path root = allowedRoot.toAbsolutePath().normalize();
            Path reportedPath = Path.of(filePath);
            Path sourcePath = (reportedPath.isAbsolute() ? reportedPath : root.resolve(reportedPath))
                    .toAbsolutePath().normalize();
            if (!sourcePath.startsWith(root) || !Files.isRegularFile(sourcePath)) {
                return limit(fallback);
            }

            List<String> lines = Files.readAllLines(sourcePath, StandardCharsets.UTF_8);
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
