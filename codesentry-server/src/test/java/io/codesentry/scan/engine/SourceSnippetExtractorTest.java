package io.codesentry.scan.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceSnippetExtractorTest {

    @TempDir
    Path workspace;

    @Test
    void extractsSourceContextAndMarksMatchedLine() throws Exception {
        Path source = workspace.resolve("Example.java");
        Files.writeString(source, "line1\nline2\nunsafe();\nline4\nline5\nline6\n");

        String snippet = SourceSnippetExtractor.extract(workspace, source.toString(), 3, 3, "fallback");

        assertTrue(snippet.contains("     1 | line1"));
        assertTrue(snippet.contains(">    3 | unsafe();"));
        assertTrue(snippet.contains("     5 | line5"));
    }

    @Test
    void refusesFilesOutsideAllowedRoot() throws Exception {
        Path outside = Files.createTempFile("codesentry-outside-", ".java");
        try {
            assertEquals("fallback", SourceSnippetExtractor.extract(workspace, outside.toString(), 1, 1,
                    "fallback"));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test void refusesEscapingSymlinksAndBinaryContent() throws Exception {
        Path outside=Files.createTempFile("codesentry-outside-", ".txt");
        try {
            Files.writeString(outside,"fixture-private-value");
            Path link=workspace.resolve("outside.txt");Files.createSymbolicLink(link,outside);
            assertEquals("fallback",SourceSnippetExtractor.extract(workspace,link.toString(),1,1,"fallback"));
            Path binary=workspace.resolve("binary.key");Files.write(binary,new byte[]{'a',0,'b'});
            assertEquals("fallback",SourceSnippetExtractor.extract(workspace,binary.toString(),1,1,"fallback"));
        } finally { Files.deleteIfExists(outside); }
    }

    @Test
    void oversizedSnippetsKeepBothEndsWithinDatabaseSafeLimit() {
        String oversized = "begin-" + "x".repeat(20000) + "-end";

        String limited = SourceSnippetExtractor.limit(oversized);

        assertEquals(SourceSnippetExtractor.MAX_SNIPPET_CHARACTERS, limited.length());
        assertTrue(limited.startsWith("begin-"));
        assertTrue(limited.endsWith("-end"));
        assertTrue(limited.contains("代码片段过长"));
    }
}
