package io.codesentry.scan.engine;

import io.codesentry.config.CodeSentryProperties;
import io.codesentry.process.CommandExecutor;
import io.codesentry.process.CommandResult;
import io.codesentry.project.model.ScanProject;
import io.codesentry.scan.model.ScanMode;
import io.codesentry.scan.model.WorkspaceMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class SpotBugsScannerAdapterTest {

    @TempDir
    Path temp;

    private SpotBugsScannerAdapter adapter() {
        return new SpotBugsScannerAdapter(mock(CommandExecutor.class), new CodeSentryProperties());
    }

    @Test
    void standardAndRenamedJarArchivesAreDiscovered() throws Exception {
        Files.createFile(temp.resolve("first.jar"));
        Files.createFile(temp.resolve("legacy.jar37"));
        Files.createFile(temp.resolve("ignore.zip"));

        List<Path> archives = adapter().findArchives(temp);

        assertEquals(2, archives.size());
        assertTrue(archives.stream().anyMatch(path -> path.endsWith("first.jar")));
        assertTrue(archives.stream().anyMatch(path -> path.endsWith("legacy.jar37")));
    }

    @Test
    void projectFileWritesEachArchiveOnItsOwnLine() throws Exception {
        Path first = Files.createFile(temp.resolve("first.jar"));
        Path second = Files.createFile(temp.resolve("second.jar"));
        Path project = temp.resolve("spotbugs-current.fbp");

        adapter().writeProject(project, List.of(first, second));

        List<String> lines = Files.readAllLines(project);
        assertTrue(lines.stream().anyMatch(line -> line.contains(first.toString())));
        assertTrue(lines.stream().anyMatch(line -> line.contains(second.toString())));
        assertEquals(2, lines.stream().filter(line -> line.contains("<Jar>")).count());
        assertTrue(lines.stream().noneMatch(line -> line.contains(first.toString()) && line.contains(second.toString())));
    }

    @Test
    void exitOneWithCompleteReportAndNoAnalysisErrorsIsAccepted() throws Exception {
        Files.createFile(temp.resolve("legacy.jar"));
        CommandExecutor commands = mock(CommandExecutor.class);
        when(commands.execute(any(), anyList(), any(Duration.class))).thenAnswer(invocation -> {
            List<String> arguments = invocation.getArgument(1);
            Path report = Path.of(arguments.get(arguments.indexOf("-output") + 1));
            Files.writeString(report, "<BugCollection>"
                    + "<FindBugsSummary total_bugs=\"0\"/>"
                    + "<Errors errors=\"0\" missingClasses=\"1\"><MissingClass>legacy.Optional</MissingClass></Errors>"
                    + "</BugCollection>");
            return new CommandResult(1, "The following classes needed for analysis were missing");
        });
        SpotBugsScannerAdapter adapter = new SpotBugsScannerAdapter(commands, new CodeSentryProperties());
        ScanContext context = new ScanContext(1L, new ScanProject(), ScanMode.FULL,
                WorkspaceMode.CURRENT, temp, temp, temp, "commit", null, "current", "GENERAL");

        assertDoesNotThrow(() -> adapter.scan(context));
    }

    @Test
    void spotbugsXmlIsNormalizedWithoutLoadingExternalEntities() throws Exception {
        Path report = temp.resolve("spotbugs.xml");
        Files.writeString(report, "<BugCollection>"
                + "<BugInstance type=\"SQL_INJECTION_JDBC\" category=\"SECURITY\" priority=\"1\" rank=\"4\" cweid=\"89\">"
                + "<Class classname=\"com.srtech.Example\"/>"
                + "<Method classname=\"com.srtech.Example\" name=\"query\"/>"
                + "<SourceLine sourcepath=\"com/srtech/Example.java\" start=\"12\" end=\"14\"/>"
                + "<ShortMessage>Potential SQL injection</ShortMessage>"
                + "<LongMessage>Untrusted input reaches a JDBC query</LongMessage>"
                + "</BugInstance></BugCollection>");

        List<NormalizedFinding> findings = adapter().parseReport(report);

        assertEquals(1, findings.size());
        NormalizedFinding finding = findings.get(0);
        assertEquals("SQL_INJECTION_JDBC", finding.getRuleId());
        assertEquals(FindingSeverity.CRITICAL, finding.getSeverity());
        assertEquals("com/srtech/Example.java", finding.getFilePath());
        assertEquals("CWE-89", finding.getCwe());
        assertEquals(12, finding.getStartLine());
        assertEquals("com.srtech.Example#query", finding.getCodeSnippet());
    }
}
