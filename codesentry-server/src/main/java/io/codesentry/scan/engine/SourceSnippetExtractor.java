package io.codesentry.scan.engine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class SourceSnippetExtractor {

    private SourceSnippetExtractor() {
    }

    public static String extract(Path allowedRoot, String filePath, Integer startLine, Integer endLine,
                                 String fallback) {
        if (allowedRoot == null || filePath == null || filePath.trim().isEmpty()
                || startLine == null || startLine <= 0) {
            return fallback;
        }
        try {
            Path root = allowedRoot.toAbsolutePath().normalize();
            Path reportedPath = Path.of(filePath);
            Path sourcePath = (reportedPath.isAbsolute() ? reportedPath : root.resolve(reportedPath))
                    .toAbsolutePath().normalize();
            if (!sourcePath.startsWith(root) || !Files.isRegularFile(sourcePath)) {
                return fallback;
            }

            List<String> lines = Files.readAllLines(sourcePath, StandardCharsets.UTF_8);
            if (lines.isEmpty() || startLine > lines.size()) {
                return fallback;
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
            return snippet.toString();
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
