package io.codesentry.scan.engine;

import io.codesentry.config.CodeSentryProperties;
import io.codesentry.process.CommandExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AlibabaP3cScannerAdapterTest {

    @TempDir
    Path temp;

    @Test
    void doesNotExecuteNamingOrCommentRulesButKeepsConcurrencyChecks() throws Exception {
        Files.writeString(temp.resolve("Example.java"), "class Example {}\n");
        CommandExecutor executor = mock(CommandExecutor.class);
        org.mockito.Mockito.when(executor.execute(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            List<String> args = call.getArgument(1);
            String rules = args.get(args.indexOf("-R") + 1);
            org.junit.jupiter.api.Assertions.assertFalse(rules.contains("ali-comment.xml"));
            org.junit.jupiter.api.Assertions.assertFalse(rules.contains("ali-naming.xml"));
            org.junit.jupiter.api.Assertions.assertTrue(rules.contains("ali-concurrent.xml"));
            Path report = Path.of(args.get(args.indexOf("-r") + 1));
            Files.writeString(report, "<pmd xmlns=\"http://pmd.sourceforge.net/report/2.0.0\"/>");
            return new io.codesentry.process.CommandResult(0, "");
        });
        AlibabaP3cScannerAdapter adapter = new AlibabaP3cScannerAdapter(executor, new CodeSentryProperties());
        adapter.scan(new ScanContext(1L, new io.codesentry.project.model.ScanProject(),
                io.codesentry.scan.model.ScanMode.FULL, io.codesentry.scan.model.WorkspaceMode.CURRENT,
                temp, temp, temp.resolve("output"), null, null, "current", "LOCAL_OWASP"));
        org.mockito.Mockito.verify(executor).execute(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void p3cXmlIsNormalizedWithRelativeSourcePath() throws Exception {
        Path source = temp.resolve("src/main/java/demo/Example.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo;\npublic class Example {\n}\n");
        Path report = temp.resolve("p3c.xml");
        Files.writeString(report, "<?xml version=\"1.0\"?><pmd xmlns=\"http://pmd.sourceforge.net/report/2.0.0\">"
                + "<file name=\"" + source + "\"><violation beginline=\"2\" endline=\"3\" "
                + "rule=\"ClassMustHaveAuthorRule\" ruleset=\"AlibabaJavaComments\" priority=\"3\">"
                + "缺少包含 @author 的注释信息</violation></file></pmd>");

        AlibabaP3cScannerAdapter adapter = new AlibabaP3cScannerAdapter(
                mock(CommandExecutor.class), new CodeSentryProperties());
        List<NormalizedFinding> findings = adapter.parseReport(report, temp);

        assertEquals(1, findings.size());
        NormalizedFinding finding = findings.get(0);
        assertEquals("Alibaba-P3C.ClassMustHaveAuthorRule", finding.getRuleId());
        assertEquals("src/main/java/demo/Example.java", finding.getFilePath());
        assertEquals(FindingSeverity.MEDIUM, finding.getSeverity());
        assertEquals("     1 | package demo;\n>    2 | public class Example {\n>    3 | }", finding.getCodeSnippet());
    }
}
