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
