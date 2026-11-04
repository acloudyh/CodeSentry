package io.codesentry.report.service;

import io.codesentry.ai.dto.AiReviewSummary;
import io.codesentry.ai.model.AiReviewRecord;
import io.codesentry.ai.service.AiReviewService;
import io.codesentry.finding.model.FindingView;
import io.codesentry.finding.service.FindingPersistenceService;
import io.codesentry.project.model.ScanProject;
import io.codesentry.project.service.ScanProjectService;
import io.codesentry.scan.model.ScanMode;
import io.codesentry.scan.model.ScanRun;
import io.codesentry.scan.service.ScanRunService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemediationReportServiceTest {
    private ReportSnapshotStore snapshotStore() {
        ReportSnapshotStore store = mock(ReportSnapshotStore.class);
        when(store.read(org.mockito.ArgumentMatchers.anyLong())).thenReturn(null);
        when(store.freeze(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(call -> call.getArgument(1));
        return store;
    }


    @Test
    void reportUsesAiGateButHumanConfirmationOverridesAiFalsePositive() {
        ScanRunService runs = mock(ScanRunService.class);
        ScanProjectService projects = mock(ScanProjectService.class);
        FindingPersistenceService findings = mock(FindingPersistenceService.class);
        AiReviewService aiReviews = mock(AiReviewService.class);
        RemediationReportService service = new RemediationReportService(runs, projects, findings, aiReviews, snapshotStore());

        ScanRun run = new ScanRun();
        run.setStatus(io.codesentry.scan.model.ScanRunStatus.COMPLETED);
        run.setId(8L);
        run.setProjectId(1L);
        run.setScanMode(ScanMode.FULL);
        run.setRuleProfile("GENERAL");
        ScanProject project = new ScanProject();
        project.setId(1L);
        project.setProjectName("review-target");

        FindingView autoExcluded = finding(101L, "AI_AUTO_EXCLUDED", null);
        FindingView humanOverride = finding(102L, "HUMAN_CONFIRMED_VISIBLE", "CONFIRMED");
        FindingView disagreement = finding(103L, "AI_DISAGREEMENT_VISIBLE", null);
        FindingView manualExcluded = finding(104L, "MANUAL_FALSE_POSITIVE", "FALSE_POSITIVE");
        FindingView oldProtocol = finding(105L, "OLD_AI_PROTOCOL_VISIBLE", null);
        FindingView differentEvidence = finding(106L, "DIFFERENT_AI_EVIDENCE_VISIBLE", null);

        AiReviewSummary oldProtocolReview = aiSummary(105L, "FALSE_POSITIVE", true, false, "COMPLETED");
        oldProtocolReview.getPrimaryReview().setPromptVersion("codesentry-ai-review-v1");
        oldProtocolReview.getVerificationReview().setPromptVersion("codesentry-ai-review-v1");
        AiReviewSummary differentEvidenceReview = aiSummary(106L, "FALSE_POSITIVE", true, false, "COMPLETED");
        differentEvidenceReview.getVerificationReview().setInputHash("different-evidence-hash");

        when(runs.requireById(8L)).thenReturn(run);
        when(projects.requireById(1L)).thenReturn(project);
        when(findings.findByRunId(8L)).thenReturn(List.of(
                autoExcluded, humanOverride, disagreement, manualExcluded, oldProtocol, differentEvidence));
        when(aiReviews.latestForRun(8L)).thenReturn(List.of(
                aiSummary(101L, "FALSE_POSITIVE", true, false, "COMPLETED"),
                aiSummary(102L, "FALSE_POSITIVE", true, false, "COMPLETED"),
                aiSummary(103L, "NEEDS_HUMAN", false, true, "NEEDS_HUMAN"),
                oldProtocolReview, differentEvidenceReview));

        String html = new String(service.exportHtml(8L), StandardCharsets.UTF_8);

        assertFalse(html.contains("AI_AUTO_EXCLUDED"));
        assertFalse(html.contains("MANUAL_FALSE_POSITIVE"));
        assertTrue(html.contains("HUMAN_CONFIRMED_VISIBLE"));
        assertTrue(html.contains("AI_DISAGREEMENT_VISIBLE"));
        assertFalse(html.contains("OLD_AI_PROTOCOL_VISIBLE"));
        assertFalse(html.contains("DIFFERENT_AI_EVIDENCE_VISIBLE"));
        assertFalse(html.contains("误报已排除"));
        assertFalse(html.contains("默认报告已排除"));
    }

    @Test
    void reportListUsesTheSameFilteredCountsAsTheExport() {
        ScanRunService runs = mock(ScanRunService.class);
        ScanProjectService projects = mock(ScanProjectService.class);
        FindingPersistenceService findings = mock(FindingPersistenceService.class);
        AiReviewService aiReviews = mock(AiReviewService.class);
        RemediationReportService service = new RemediationReportService(runs, projects, findings, aiReviews, snapshotStore());
        ScanRun run = new ScanRun();
        run.setStatus(io.codesentry.scan.model.ScanRunStatus.COMPLETED);
        run.setId(8L); run.setProjectId(1L); run.setScanMode(ScanMode.FULL);
        run.setStatus(io.codesentry.scan.model.ScanRunStatus.COMPLETED);
        ScanProject project = new ScanProject(); project.setId(1L); project.setProjectName("review-target");
        when(runs.recent(100)).thenReturn(List.of(run));
        when(runs.requireById(8L)).thenReturn(run);
        when(projects.requireById(1L)).thenReturn(project);
        when(findings.findByRunId(8L)).thenReturn(List.of(
                finding(101L, "VISIBLE", null), finding(102L, "EXCLUDED", "FALSE_POSITIVE")));
        when(aiReviews.latestForRun(8L)).thenReturn(List.of(aiSummary(101L, "CONFIRMED", true, false, "COMPLETED")));

        io.codesentry.report.dto.RemediationReportSummary summary = service.recent(100).get(0);
        String html = new String(service.exportHtml(8L), StandardCharsets.UTF_8);

        assertEquals(1, summary.getTotalFindings());
        assertEquals(1, summary.getNewFindings());
        assertTrue(html.contains("VISIBLE"));
        assertFalse(html.contains("EXCLUDED"));
    }

    @Test
    void reportListIsSortedByScanTimeDescendingAndExposesTheTime() {
        ScanRunService runs = mock(ScanRunService.class);
        ScanProjectService projects = mock(ScanProjectService.class);
        FindingPersistenceService findings = mock(FindingPersistenceService.class);
        AiReviewService reviews = mock(AiReviewService.class);
        RemediationReportService service = new RemediationReportService(runs, projects, findings, reviews, snapshotStore());
        ScanRun older = reportRun(31L, LocalDateTime.of(2026, 10, 30, 8, 0));
        ScanRun newer = reportRun(35L, LocalDateTime.of(2026, 11, 1, 9, 30));
        ScanProject project = new ScanProject(); project.setId(1L); project.setProjectName("测试项目");
        when(runs.recent(100)).thenReturn(List.of(older, newer));
        when(runs.requireById(31L)).thenReturn(older);
        when(runs.requireById(35L)).thenReturn(newer);
        when(projects.requireById(1L)).thenReturn(project);
        when(findings.findByRunId(31L)).thenReturn(List.of());
        when(findings.findByRunId(35L)).thenReturn(List.of());
        when(reviews.latestForRun(31L)).thenReturn(List.of());
        when(reviews.latestForRun(35L)).thenReturn(List.of());

        List<io.codesentry.report.dto.RemediationReportSummary> result = service.recent(100);

        assertEquals(List.of(35L, 31L), result.stream()
                .map(io.codesentry.report.dto.RemediationReportSummary::getId)
                .collect(java.util.stream.Collectors.toList()));
        assertEquals(LocalDateTime.of(2026, 11, 1, 9, 30), result.get(0).getCreatedAt());
    }

    @Test
    void cvesForAnySameDependencyAndVersionAreMergedWithoutLosingDetails() throws Exception {
        ScanRunService runs = mock(ScanRunService.class);
        ScanProjectService projects = mock(ScanProjectService.class);
        FindingPersistenceService findings = mock(FindingPersistenceService.class);
        AiReviewService reviews = mock(AiReviewService.class);
        RemediationReportService service = new RemediationReportService(runs, projects, findings, reviews, snapshotStore());
        ScanRun run = reportRun(8L, LocalDateTime.of(2026, 11, 1, 10, 0));
        ScanProject project = new ScanProject(); project.setId(1L); project.setProjectName("测试项目");
        FindingView first = log4jFinding(201L, "CVE-2022-23302", "JMSSink 远程代码执行");
        FindingView second = log4jFinding(202L, "CVE-2021-4104", "JMSAppender 远程代码执行");
        FindingView poiFirst = dependencyFinding(203L, "CVE-2017-5644", "Apache POI 远程攻击",
                "src/main/webapp/WEB-INF/lib/poi-3.7.jar37", "org.apache.poi:poi@3.7");
        FindingView poiSecond = dependencyFinding(204L, "CVE-2014-9527", "POI 拒绝服务",
                "src/main/webapp/WEB-INF/lib/poi-3.7.jar37", "org.apache.poi:poi@3.7");
        poiFirst.setRecommendation("升级到修复版本：3.17");
        poiSecond.setRecommendation("升级到修复版本：4.1.1");
        FindingView poiOtherVersion = dependencyFinding(205L, "CVE-2099-0001", "POI 另一版本风险",
                "src/main/webapp/WEB-INF/lib/poi-4.0.jar", "org.apache.poi:poi@4.0");
        when(runs.requireById(8L)).thenReturn(run);
        when(projects.requireById(1L)).thenReturn(project);
        when(findings.findByRunId(8L)).thenReturn(List.of(first, second, poiFirst, poiSecond, poiOtherVersion));
        when(reviews.latestForRun(8L)).thenReturn(List.of());

        String html = new String(service.exportHtml(8L), StandardCharsets.UTF_8);
        byte[] docxBytes = service.exportDocx(8L);
        String word;
        try (org.apache.poi.xwpf.usermodel.XWPFDocument document = new org.apache.poi.xwpf.usermodel.XWPFDocument(
                new java.io.ByteArrayInputStream(docxBytes))) {
            word = document.getParagraphs().stream().map(org.apache.poi.xwpf.usermodel.XWPFParagraph::getText)
                    .collect(java.util.stream.Collectors.joining("\n"));
        }

        for (String report : List.of(html, word)) {
            assertTrue(report.contains("Log4j 组件存在 2 个已知漏洞（合并整改）"));
            assertTrue(report.contains("CVE-2022-23302"));
            assertTrue(report.contains("CVE-2021-4104"));
            assertTrue(report.contains("JMSSink 远程代码执行"));
            assertTrue(report.contains("JMSAppender 远程代码执行"));
            assertTrue(report.contains("POI 组件存在 2 个已知漏洞（合并整改）"));
            assertTrue(report.contains("CVE-2017-5644：3.17"));
            assertTrue(report.contains("CVE-2014-9527：4.1.1"));
            assertTrue(report.contains("建议目标：迁移至 Log4j 2.26.1"));
            assertFalse(report.contains("请查阅漏洞公告"));
            assertTrue(report.contains("CVE-2017-5644"));
            assertTrue(report.contains("CVE-2014-9527"));
            assertTrue(report.contains("POI 另一版本风险"));
            assertFalse(report.contains("POI 组件存在 3 个已知漏洞（合并整改）"));
        }
        assertTrue(html.contains("统计仍按 CVE 数量计算"));
    }

    @Test
    void dependencyVulnerabilityReportShowsJarPathAndExplainsMissingSourceLine() {
        ScanRunService runs = mock(ScanRunService.class);
        ScanProjectService projects = mock(ScanProjectService.class);
        FindingPersistenceService findings = mock(FindingPersistenceService.class);
        AiReviewService reviews = mock(AiReviewService.class);
        RemediationReportService service = new RemediationReportService(runs, projects, findings, reviews, snapshotStore());
        ScanRun run = new ScanRun();
        run.setStatus(io.codesentry.scan.model.ScanRunStatus.COMPLETED); run.setId(8L); run.setProjectId(1L); run.setScanMode(ScanMode.FULL);
        ScanProject project = new ScanProject(); project.setId(1L); project.setProjectName("测试项目");
        FindingView dependency = finding(101L, "依赖漏洞", "CONFIRMED");
        dependency.setScanner("Trivy");
        dependency.setCategory("VULNERABILITY");
        dependency.setFilePath("src/main/webapp/WEB-INF/lib/dom4j-1.6.1.jar");
        dependency.setStartLine(null);
        when(runs.requireById(8L)).thenReturn(run);
        when(projects.requireById(1L)).thenReturn(project);
        when(findings.findByRunId(8L)).thenReturn(List.of(dependency));
        when(reviews.latestForRun(8L)).thenReturn(List.of());

        String html = new String(service.exportHtml(8L), StandardCharsets.UTF_8);

        assertTrue(html.contains("src/main/webapp/WEB-INF/lib/dom4j-1.6.1.jar"));
        assertTrue(html.contains("依赖组件漏洞，无源码行号"));
        assertFalse(html.contains("Java:-"));
    }

    private FindingView finding(Long id, String title, String triageStatus) {
        FindingView finding = new FindingView();
        finding.setId(id);
        finding.setScanRunId(8L);
        finding.setScanner("Semgrep");
        finding.setRuleId("test-rule");
        finding.setSeverity("HIGH");
        finding.setTitle(title);
        finding.setFilePath("src/Test.java");
        finding.setStartLine(id.intValue());
        finding.setResultStatus("NEW");
        finding.setTriageStatus(triageStatus);
        return finding;
    }

    private ScanRun reportRun(Long id, LocalDateTime createdAt) {
        ScanRun run = new ScanRun();
        run.setStatus(io.codesentry.scan.model.ScanRunStatus.COMPLETED);
        run.setId(id); run.setProjectId(1L); run.setScanMode(ScanMode.FULL);
        run.setStatus(io.codesentry.scan.model.ScanRunStatus.COMPLETED);
        run.setCreatedAt(createdAt);
        return run;
    }

    @Test
    void projectRuntimeControlsHtmlAndWordAdviceWithoutChangingArchivedMembers() throws Exception {
        ScanRunService runs = mock(ScanRunService.class);
        ScanProjectService projects = mock(ScanProjectService.class);
        FindingPersistenceService findings = mock(FindingPersistenceService.class);
        AiReviewService reviews = mock(AiReviewService.class);
        RemediationReportService service = new RemediationReportService(runs, projects, findings, reviews, snapshotStore());
        ScanRun run = reportRun(8L, LocalDateTime.of(2026,11,3,10,0));
        ScanProject project = new ScanProject();project.setId(1L);project.setProjectName("Java8业务");project.setTargetJavaVersion(8);
        FindingView poi = dependencyFinding(203L,"CVE-2019-12415","POI XXE","lib/poi-3.7.jar37","org.apache.poi:poi@3.7");
        when(runs.requireById(8L)).thenReturn(run);when(projects.requireById(1L)).thenReturn(project);
        when(findings.findByRunId(8L)).thenReturn(List.of(poi));when(reviews.latestForRun(8L)).thenReturn(List.of());
        String html = new String(service.exportHtml(8L),StandardCharsets.UTF_8);
        assertTrue(html.contains("业务 Java 版本：Java 8"));assertTrue(html.contains("统一 POI 组件至 5.5.1"));
        try (org.apache.poi.xwpf.usermodel.XWPFDocument document = new org.apache.poi.xwpf.usermodel.XWPFDocument(
                new java.io.ByteArrayInputStream(service.exportDocx(8L)))) {
            String text = document.getParagraphs().stream().map(org.apache.poi.xwpf.usermodel.XWPFParagraph::getText)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(text.contains("业务 Java 版本：Java 8"));assertTrue(text.contains("统一 POI 组件至 5.5.1"));
        }
        project.setTargetJavaVersion(null);
        assertFalse(new String(service.exportHtml(8L),StandardCharsets.UTF_8).contains("统一 POI 组件至 5.5.1"));
        assertEquals("升级到修复版本。",poi.getRecommendation());
    }

    private FindingView log4jFinding(Long id, String ruleId, String title) {
        FindingView finding = finding(id, title, "CONFIRMED");
        finding.setScanner("Trivy");
        finding.setCategory("VULNERABILITY");
        finding.setRuleId(ruleId);
        finding.setSeverity("HIGH");
        finding.setFilePath("src/main/webapp/WEB-INF/lib/log4j-1.2.8.jar128");
        finding.setStartLine(null);
        finding.setCodeSnippet("log4j:log4j@1.2.8");
        finding.setRecommendation("升级到修复版本。");
        return finding;
    }

    private FindingView dependencyFinding(Long id, String ruleId, String title, String path, String coordinate) {
        FindingView finding = finding(id, title, "CONFIRMED");
        finding.setScanner("Trivy");
        finding.setCategory("VULNERABILITY");
        finding.setRuleId(ruleId);
        finding.setSeverity("MEDIUM");
        finding.setFilePath(path);
        finding.setStartLine(null);
        finding.setCodeSnippet(coordinate);
        finding.setRecommendation("升级到修复版本。");
        return finding;
    }

    @Test
    void allReportFormatsUseToolTerminologyIncludingHistoricalCommentsAndFailures() throws Exception {
        ScanRunService runs = mock(ScanRunService.class);
        ScanProjectService projects = mock(ScanProjectService.class);
        FindingPersistenceService findings = mock(FindingPersistenceService.class);
        AiReviewService reviews = mock(AiReviewService.class);
        RemediationReportService service = new RemediationReportService(runs, projects, findings, reviews, snapshotStore());
        ScanRun run = new ScanRun();
        run.setStatus(io.codesentry.scan.model.ScanRunStatus.COMPLETED); run.setId(8L); run.setProjectId(1L); run.setScanMode(ScanMode.FULL);
        ScanProject project = new ScanProject(); project.setId(1L); project.setProjectName("测试项目");
        FindingView candidate = finding(101L, "测试“风险”与 <script>", "CONFIRMED");
        candidate.setTriageComment("AI 双轮复核建议：DeepSeek 提供风险线索，人工已确认。");
        AiReviewSummary failed = aiSummary(101L, "CONFIRMED", false, false, "FAILED");
        failed.setErrorMessage("AI返回内容因输出长度限制被截断");
        when(runs.requireById(8L)).thenReturn(run);
        when(projects.requireById(1L)).thenReturn(project);
        when(findings.findByRunId(8L)).thenReturn(List.of(candidate));
        when(reviews.latestForRun(8L)).thenReturn(List.of(failed));
        String html = new String(service.exportHtml(8L), StandardCharsets.UTF_8);
        assertTrue(html.contains("测试“风险”与 &lt;script&gt;"));
        assertTrue(html.contains("'CodeSentrySong','SimSun'"));
        assertTrue(html.contains("'CodeSentryHei','SimHei'"));
        byte[] docxBytes = service.exportDocx(8L);
        List<String> docxEntries = zipEntries(docxBytes);
        assertTrue(docxEntries.contains("word/fontTable.xml"));
        assertTrue(docxEntries.contains("word/fonts/simsun.odttf"));
        assertTrue(docxEntries.contains("word/fonts/simhei.odttf"));
        try (org.apache.poi.xwpf.usermodel.XWPFDocument doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(
                new java.io.ByteArrayInputStream(docxBytes));
             org.apache.pdfbox.pdmodel.PDDocument pdf = org.apache.pdfbox.pdmodel.PDDocument.load(service.exportPdf(8L))) {
            assertEquals("黑体", doc.getParagraphs().get(0).getRuns().get(0)
                    .getFontFamily(org.apache.poi.xwpf.usermodel.XWPFRun.FontCharRange.eastAsia));
            assertEquals("宋体", doc.getParagraphs().get(2).getRuns().get(0)
                    .getFontFamily(org.apache.poi.xwpf.usermodel.XWPFRun.FontCharRange.eastAsia));
            String word = doc.getParagraphs().stream().map(org.apache.poi.xwpf.usermodel.XWPFParagraph::getText)
                    .collect(java.util.stream.Collectors.joining("\n"));
            String pdfText = new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
            for (String text : List.of(html, word, pdfText)) {
                assertTrue(text.contains("工具"));
                assertFalse(text.contains("AI"));
                assertFalse(text.contains("DeepSeek"));
                assertFalse(text.contains("双轮"));
                assertFalse(text.contains("两轮"));
            }
        }
        assertEquals("AI 双轮复核建议：DeepSeek 提供风险线索，人工已确认。", candidate.getTriageComment());
        assertEquals("AI返回内容因输出长度限制被截断", failed.getErrorMessage());
    }

    private List<String> zipEntries(byte[] content) throws Exception {
        List<String> entries = new java.util.ArrayList<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(content))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) entries.add(entry.getName());
        }
        return entries;
    }

    private AiReviewSummary aiSummary(Long findingId, String verdict, boolean highConfidence,
                                      boolean disagreement, String status) {
        AiReviewSummary summary = new AiReviewSummary();
        summary.setFindingId(findingId);
        summary.setScanRunId(8L);
        summary.setConsolidatedVerdict(verdict);
        summary.setConfidence(highConfidence ? 0.91 : 0.70);
        summary.setHighConfidence(highConfidence);
        summary.setDisagreement(disagreement);
        summary.setStatus(status);
        summary.setPrimaryReview(aiRecord("PRIMARY"));
        summary.setVerificationReview(aiRecord("VERIFY"));
        for (AiReviewRecord record : List.of(summary.getPrimaryReview(), summary.getVerificationReview())) {
            record.setFindingId(findingId); record.setScanRunId(8L); record.setReviewJobId(1L);
            record.setVerdict("NEEDS_HUMAN".equals(verdict) ? "CONDITIONAL" : verdict);
            record.setConfidence(new java.math.BigDecimal(highConfidence ? "0.91" : "0.70"));
        }
        return summary;
    }

    private AiReviewRecord aiRecord(String round) {
        AiReviewRecord record = new AiReviewRecord();
        record.setReviewRound(round);
        record.setVerdict("FALSE_POSITIVE");
        record.setStatus("COMPLETED");
        record.setPromptVersion(AiReviewService.PROMPT_VERSION);
        record.setInputHash("same-evidence-hash");
        return record;
    }
}
