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
    @Test void inclusionIdsReusePolicyWithoutReadingFullReportOrSourceFiles() {
        ScanRunService runs = mock(ScanRunService.class);
        ScanProjectService projects = mock(ScanProjectService.class);
        FindingPersistenceService findings = mock(FindingPersistenceService.class);
        AiReviewService ai = mock(AiReviewService.class);
        ReportSnapshotStore snapshots = mock(ReportSnapshotStore.class);
        RemediationReportService service = new RemediationReportService(runs, projects, findings, ai, snapshots);
        ScanRun run = new ScanRun(); run.setId(8L); run.setProjectId(1L);
        when(runs.requireById(8L)).thenReturn(run);
        when(snapshots.readIds(8L)).thenReturn(null);
        FindingView confirmed = finding(101L, "confirmed", "CONFIRMED");
        FindingView pending = finding(102L, "pending", null);
        when(findings.findPolicyByRunId(8L)).thenReturn(List.of(confirmed, pending));
        when(ai.latestPolicyForRun(8L)).thenReturn(List.of());
        assertEquals(List.of(101L), service.includedFindingIds(8L));
        org.mockito.Mockito.verify(findings, org.mockito.Mockito.never()).findByRunId(8L);
        org.mockito.Mockito.verify(runs).requireById(8L);
        org.mockito.Mockito.verify(projects).requireById(1L);
        org.mockito.Mockito.clearInvocations(findings, ai);
        when(snapshots.readIds(8L)).thenReturn(List.of(102L));
        assertEquals(List.of(102L), service.includedFindingIds(8L));
        when(snapshots.readIds(8L)).thenReturn(List.of());
        assertEquals(List.of(), service.includedFindingIds(8L));
        org.mockito.Mockito.verifyNoInteractions(findings, ai);
    }

    @Test void largeReportListUsesMetadataAndFrozenCountsWithoutSourceReadback() {
        ScanRunService runs=mock(ScanRunService.class); FindingPersistenceService findings=mock(FindingPersistenceService.class);
        AiReviewService reviews=mock(AiReviewService.class); ReportSnapshotStore snapshots=snapshotStore();
        RemediationReportService service=new RemediationReportService(runs,mock(ScanProjectService.class),findings,reviews,snapshots);
        when(runs.recentForReports(100,null,null)).thenReturn(List.of(reportRun(99L,LocalDateTime.now())));
        List<FindingView> raw=new java.util.ArrayList<>();
        for(int n=0;n<10000;n++) { FindingView f=finding((long)n,"large",n%2==0?"CONFIRMED":null);f.setFilePath("src/"+n+".java");raw.add(f); }
        when(findings.findPolicyByRunId(99L)).thenReturn(raw);
        when(reviews.latestPolicyForRun(99L)).thenReturn(List.of());
        io.codesentry.report.dto.RemediationReportSummary summary=service.recent(100).get(0);
        assertEquals(10000,summary.getLedgerFindings()); assertEquals(5000,summary.getTotalFindings());assertEquals(5000,summary.getNotIncludedFindings());
        FindingView archived=finding(1L,"archived",null); archived.setResultStatus("REOPENED");
        when(snapshots.readSummary(99L)).thenReturn(List.of(archived));
        summary=service.recent(100).get(0);
        assertEquals(1,summary.getTotalFindings()); assertEquals(1,summary.getNewFindings()); assertEquals(9999,summary.getNotIncludedFindings());
        org.mockito.Mockito.verify(findings,org.mockito.Mockito.never()).findStoredByRunId(99L);
        org.mockito.Mockito.verify(reviews,org.mockito.Mockito.never()).latestForRun(99L);
    }

    @Test void largeHtmlReportRestoresSourceOnlyForIncludedFindings() {
        ScanRunService runs=mock(ScanRunService.class); FindingPersistenceService findings=mock(FindingPersistenceService.class);
        AiReviewService reviews=mock(AiReviewService.class); ScanProjectService projects=mock(ScanProjectService.class);
        RemediationReportService service=new RemediationReportService(runs,projects,findings,reviews,snapshotStore());
        ScanRun run=reportRun(99L,LocalDateTime.now()); when(runs.requireById(99L)).thenReturn(run);
        ScanProject project=new ScanProject();project.setId(1L);project.setProjectName("大报告");when(projects.requireById(1L)).thenReturn(project);
        List<FindingView> raw=new java.util.ArrayList<>();
        for(int n=0;n<10000;n++) { FindingView f=finding((long)n,"large",n==0?"CONFIRMED":null);f.setFilePath("src/"+n+".java");raw.add(f); }
        when(findings.findStoredByRunId(99L)).thenReturn(raw);when(reviews.latestForRun(99L)).thenReturn(List.of());
        String html=new String(service.exportHtml(99L),StandardCharsets.UTF_8);
        assertTrue(html.contains("10000 条台账问题 = 1 条纳入整改 + 9999 条未纳入"));
        org.mockito.Mockito.verify(findings).enrichSnippets(List.of(raw.get(0)));
        org.mockito.Mockito.verify(findings,org.mockito.Mockito.never()).findByRunId(99L);
    }

    @Test void qualityIsSeparatedFromNewSecurityReportsButArchivedScopeStaysFrozen() {
        ScanRunService runs=mock(ScanRunService.class);ScanProjectService projects=mock(ScanProjectService.class);FindingPersistenceService findings=mock(FindingPersistenceService.class);AiReviewService reviews=mock(AiReviewService.class);ReportSnapshotStore snapshots=snapshotStore();
        RemediationReportService service=new RemediationReportService(runs,projects,findings,reviews,snapshots);
        ScanRun run=reportRun(99L,LocalDateTime.now());when(runs.recentForReports(100,null,null)).thenReturn(List.of(run));when(runs.requireById(99L)).thenReturn(run);
        ScanProject project=new ScanProject();project.setId(1L);project.setProjectName("分类报告");when(projects.requireById(1L)).thenReturn(project);
        FindingView quality=finding(1L,"命名规范","CONFIRMED");quality.setRuleId("Alibaba-P3C.LowerCamelCaseVariableNamingRule");
        FindingView risk=finding(2L,"安全风险","CONFIRMED");risk.setFilePath("risk.java");
        when(findings.findPolicyByRunId(99L)).thenReturn(List.of(quality,risk));when(findings.findStoredByRunId(99L)).thenReturn(List.of(quality,risk));
        var summary=service.recent(100).get(0);assertEquals(1,summary.getLedgerFindings());assertEquals(1,summary.getTotalFindings());assertEquals(1,summary.getQualityFindings());
        String html=new String(service.exportHtml(99L),StandardCharsets.UTF_8);assertTrue(html.contains("1 条台账问题 = 1 条纳入整改 + 0 条未纳入"));assertTrue(html.contains("1 条规范问题另行维护"));
        // 安全报告归档后再次打开，仍不能把数千条旧规范塞回未纳入附录或列表总数。
        when(snapshots.read(99L)).thenReturn(List.of(risk));when(snapshots.readSummary(99L)).thenReturn(List.of(risk));
        html=new String(service.exportHtml(99L),StandardCharsets.UTF_8);assertTrue(html.contains("1 条台账问题 = 1 条纳入整改 + 0 条未纳入"));
        assertFalse(html.contains("LowerCamelCaseVariableNamingRule"));assertEquals(1,service.recent(100).get(0).getLedgerFindings());
        AiReviewSummary completed=new AiReviewSummary();completed.setFindingId(2L);completed.setStatus("COMPLETED");
        AiReviewRecord verification=new AiReviewRecord();verification.setStatus("COMPLETED");verification.setRecommendation("针对本文件改用参数绑定，并增加输入验证。");completed.setVerificationReview(verification);
        when(reviews.latestForRun(99L)).thenReturn(List.of(completed));
        html=new String(service.exportHtml(99L),StandardCharsets.UTF_8);assertTrue(html.contains("工具复核补充建议（需人工确认）"));assertTrue(html.contains(verification.getRecommendation()));
        when(snapshots.readSummary(99L)).thenReturn(List.of(quality,risk));summary=service.recent(100).get(0);assertEquals(2,summary.getLedgerFindings());assertEquals(2,summary.getTotalFindings());assertEquals(1,summary.getLegacyQualityInReport());
    }

    private String riskBody(String html) {
        return html.substring(html.indexOf("<tbody>"), html.indexOf("</tbody>"));
    }

    private ReportSnapshotStore snapshotStore() {
        ReportSnapshotStore store = mock(ReportSnapshotStore.class);
        when(store.read(org.mockito.ArgumentMatchers.anyLong())).thenReturn(null);
        when(store.readSummary(org.mockito.ArgumentMatchers.anyLong())).thenReturn(null);
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
        when(findings.findStoredByRunId(8L)).thenReturn(List.of(
                autoExcluded, humanOverride, disagreement, manualExcluded, oldProtocol, differentEvidence));
        when(findings.findPolicyByRunId(8L)).thenReturn(List.of(
                autoExcluded, humanOverride, disagreement, manualExcluded, oldProtocol, differentEvidence));
        when(aiReviews.latestForRun(8L)).thenReturn(List.of(
                aiSummary(101L, "FALSE_POSITIVE", true, false, "COMPLETED"),
                aiSummary(102L, "FALSE_POSITIVE", true, false, "COMPLETED"),
                aiSummary(103L, "NEEDS_HUMAN", false, true, "NEEDS_HUMAN"),
                oldProtocolReview, differentEvidenceReview));
        when(aiReviews.latestPolicyForRun(8L)).thenReturn(List.of(
                aiSummary(101L, "FALSE_POSITIVE", true, false, "COMPLETED"),
                aiSummary(102L, "FALSE_POSITIVE", true, false, "COMPLETED"),
                aiSummary(103L, "NEEDS_HUMAN", false, true, "NEEDS_HUMAN"),
                oldProtocolReview, differentEvidenceReview));

        String html = new String(service.exportHtml(8L), StandardCharsets.UTF_8);

        assertFalse(riskBody(html).contains("AI_AUTO_EXCLUDED"));
        assertFalse(riskBody(html).contains("MANUAL_FALSE_POSITIVE"));
        assertTrue(html.contains("HUMAN_CONFIRMED_VISIBLE"));
        assertTrue(html.contains("AI_DISAGREEMENT_VISIBLE"));
        assertFalse(riskBody(html).contains("OLD_AI_PROTOCOL_VISIBLE"));
        assertFalse(riskBody(html).contains("DIFFERENT_AI_EVIDENCE_VISIBLE"));
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
        when(runs.recentForReports(100,null,null)).thenReturn(List.of(run));
        when(runs.requireById(8L)).thenReturn(run);
        when(projects.requireById(1L)).thenReturn(project);
        when(findings.findStoredByRunId(8L)).thenReturn(List.of(
                finding(101L, "VISIBLE", null), finding(102L, "EXCLUDED", "FALSE_POSITIVE")));
        when(findings.findPolicyByRunId(8L)).thenReturn(List.of(
                finding(101L, "VISIBLE", null), finding(102L, "EXCLUDED", "FALSE_POSITIVE")));
        when(aiReviews.latestForRun(8L)).thenReturn(List.of(aiSummary(101L, "CONFIRMED", true, false, "COMPLETED")));
        when(aiReviews.latestPolicyForRun(8L)).thenReturn(List.of(aiSummary(101L, "CONFIRMED", true, false, "COMPLETED")));

        io.codesentry.report.dto.RemediationReportSummary summary = service.recent(100).get(0);
        String html = new String(service.exportHtml(8L), StandardCharsets.UTF_8);

        assertEquals(1, summary.getTotalFindings());
        assertEquals(1, summary.getNewFindings());
        assertTrue(html.contains("VISIBLE"));
        assertFalse(riskBody(html).contains("EXCLUDED"));
    }

    @Test
    void distinguishesLedgerIssueCountsFromMergedReportGroups() {
        ScanRunService runs = mock(ScanRunService.class);
        ScanProjectService projects = mock(ScanProjectService.class);
        FindingPersistenceService findings = mock(FindingPersistenceService.class);
        AiReviewService reviews = mock(AiReviewService.class);
        RemediationReportService service = new RemediationReportService(runs, projects, findings, reviews, snapshotStore());
        ScanRun run = reportRun(47L, LocalDateTime.of(2026, 11, 10, 17, 45));
        ScanProject project = new ScanProject(); project.setId(1L); project.setProjectName("测试项目");
        List<FindingView> raw = new java.util.ArrayList<>();
        for (int i = 0; i < 145; i++) {
            int component = i % 41;
            raw.add(dependencyFinding(1000L + i, "CVE-2026-" + (10000 + i), "组件风险",
                    "app.jar/BOOT-INF/lib/lib-" + component + "-1.0.jar", "example:lib-" + component + "@1.0"));
        }
        for (int i = 0; i < 12; i++) raw.add(finding(2000L + i, "未纳入项" + i, "FALSE_POSITIVE"));
        when(runs.recentForReports(100,null,null)).thenReturn(List.of(run));
        when(runs.requireById(47L)).thenReturn(run);
        when(projects.requireById(1L)).thenReturn(project);
        when(findings.findStoredByRunId(47L)).thenReturn(raw);
        when(findings.findPolicyByRunId(47L)).thenReturn(raw);
        when(reviews.latestForRun(47L)).thenReturn(List.of());
        when(reviews.latestPolicyForRun(47L)).thenReturn(List.of());

        io.codesentry.report.dto.RemediationReportSummary summary = service.recent(100).get(0);
        assertEquals(157, summary.getLedgerFindings());
        assertEquals(145, summary.getTotalFindings());
        assertEquals(41, summary.getDetailGroups());
        assertEquals(12, summary.getNotIncludedFindings());
        List<io.codesentry.report.dto.ReportExclusion> excluded = service.excludedFindings(47L);
        assertEquals(12, excluded.size());
        assertTrue(excluded.stream().allMatch(item -> "人工判定误报".equals(item.getReason())));
        assertEquals(12, excluded.stream().map(io.codesentry.report.dto.ReportExclusion::getFindingId).distinct().count());
        String html = new String(service.exportHtml(47L), StandardCharsets.UTF_8);
        assertTrue(html.contains("157 条台账问题 = 145 条纳入整改 + 12 条未纳入"));
        String body = html.substring(html.indexOf("<tbody>"), html.indexOf("</tbody>"));
        assertEquals(41, body.split("</td></tr>", -1).length - 1);
        assertTrue(html.contains("合并为 41 组整改项"));
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
        when(runs.recentForReports(100,null,null)).thenReturn(List.of(older, newer));
        when(runs.requireById(31L)).thenReturn(older);
        when(runs.requireById(35L)).thenReturn(newer);
        when(projects.requireById(1L)).thenReturn(project);
        when(findings.findStoredByRunId(31L)).thenReturn(List.of());
        when(findings.findPolicyByRunId(31L)).thenReturn(List.of());
        when(findings.findStoredByRunId(35L)).thenReturn(List.of());
        when(findings.findPolicyByRunId(35L)).thenReturn(List.of());
        when(reviews.latestForRun(31L)).thenReturn(List.of());
        when(reviews.latestPolicyForRun(31L)).thenReturn(List.of());
        when(reviews.latestForRun(35L)).thenReturn(List.of());
        when(reviews.latestPolicyForRun(35L)).thenReturn(List.of());

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
        ScanProject project = new ScanProject(); project.setTargetJavaVersion(11); project.setId(1L); project.setProjectName("测试项目");
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
        when(findings.findStoredByRunId(8L)).thenReturn(List.of(first, second, poiFirst, poiSecond, poiOtherVersion));
        when(findings.findPolicyByRunId(8L)).thenReturn(List.of(first, second, poiFirst, poiSecond, poiOtherVersion));
        when(reviews.latestForRun(8L)).thenReturn(List.of());
        when(reviews.latestPolicyForRun(8L)).thenReturn(List.of());

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
            assertTrue(report.contains("5 条台账问题 = 5 条纳入整改 + 0 条未纳入"));
            assertFalse(report.contains("POI 组件存在 3 个已知漏洞（合并整改）"));
        }
        assertEquals(3, riskBody(html).split("</td></tr>", -1).length - 1);
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
        when(findings.findStoredByRunId(8L)).thenReturn(List.of(dependency));
        when(findings.findPolicyByRunId(8L)).thenReturn(List.of(dependency));
        when(reviews.latestForRun(8L)).thenReturn(List.of());
        when(reviews.latestPolicyForRun(8L)).thenReturn(List.of());

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
        when(findings.findStoredByRunId(8L)).thenReturn(List.of(poi));
        when(findings.findPolicyByRunId(8L)).thenReturn(List.of(poi));when(reviews.latestForRun(8L)).thenReturn(List.of());
        when(reviews.latestPolicyForRun(8L)).thenReturn(List.of());
        String html = new String(service.exportHtml(8L),StandardCharsets.UTF_8);
        assertTrue(html.contains("业务 Java 版本：Java 8"));assertTrue(html.contains("统一 POI 组件至 5.5.1"));
        assertFalse(html.contains("规则集：")); assertFalse(html.contains("两者分别统计"));
        assertFalse(html.contains("项目约束："));
        assertEquals(1, html.split("业务 Java 版本：", -1).length - 1);
        try (org.apache.poi.xwpf.usermodel.XWPFDocument document = new org.apache.poi.xwpf.usermodel.XWPFDocument(
                new java.io.ByteArrayInputStream(service.exportDocx(8L)))) {
            String text = document.getParagraphs().stream().map(org.apache.poi.xwpf.usermodel.XWPFParagraph::getText)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(text.contains("业务 Java 版本：Java 8"));assertTrue(text.contains("统一 POI 组件至 5.5.1"));
            assertFalse(text.contains("规则集：")); assertFalse(text.contains("两者分别统计"));
            assertFalse(document.getDocument().xmlText().contains("项目约束："));
        }
        project.setTargetJavaVersion(null);
        assertFalse(new String(service.exportHtml(8L),StandardCharsets.UTF_8).contains("统一 POI 组件至 5.5.1"));
        assertEquals("升级到修复版本。",poi.getRecommendation());
    }

    @Test
    void actionableJarAdviceIsRenderedInHtmlAndWordWithoutChangingScannerEvidence() throws Exception {
        ScanRunService runs = mock(ScanRunService.class);
        ScanProjectService projects = mock(ScanProjectService.class);
        FindingPersistenceService findings = mock(FindingPersistenceService.class);
        AiReviewService reviews = mock(AiReviewService.class);
        RemediationReportService service = new RemediationReportService(runs, projects, findings, reviews, snapshotStore());
        ScanRun run = reportRun(8L, LocalDateTime.of(2026,11,6,10,0));
        ScanProject project = new ScanProject(); project.setId(1L); project.setProjectName("JAR 整改"); project.setTargetJavaVersion(8);
        FindingView dependency = dependencyFinding(204L, "CVE-2023-34462", "Netty TLS", "app.jar/BOOT-INF/lib/nacos-client-2.2.0.jar", "io.netty:netty-handler@4.1.48.Final");
        dependency.setRecommendation("升级到修复版本：4.1.94.Final");
        when(runs.requireById(8L)).thenReturn(run); when(projects.requireById(1L)).thenReturn(project);
        when(findings.findStoredByRunId(8L)).thenReturn(List.of(dependency));
        when(findings.findPolicyByRunId(8L)).thenReturn(List.of(dependency)); when(reviews.latestForRun(8L)).thenReturn(List.of());
        when(reviews.latestPolicyForRun(8L)).thenReturn(List.of());
        String html = new String(service.exportHtml(8L), StandardCharsets.UTF_8);
        assertTrue(html.contains("需升级或重建外层包"));
        assertFalse(html.contains("整改实施步骤 · 问题组"));
        assertFalse(html.contains("class=\"remediation-row\""));
        assertTrue(html.contains("外层包 nacos-client-2.2.0.jar"));
        assertTrue(html.contains("仅修改业务侧依赖版本无效"));
        assertTrue(html.contains("上传新包全量复扫"));
        try (org.apache.poi.xwpf.usermodel.XWPFDocument document = new org.apache.poi.xwpf.usermodel.XWPFDocument(
                new java.io.ByteArrayInputStream(service.exportDocx(8L)))) {
            String xml = document.getDocument().xmlText();
            assertTrue(xml.contains("需升级或重建外层包"));
            assertTrue(xml.contains("外层包 nacos-client-2.2.0.jar"));
            assertTrue(xml.contains("TLS 握手"));
        }
        assertEquals("升级到修复版本：4.1.94.Final", dependency.getRecommendation());
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
        when(findings.findStoredByRunId(8L)).thenReturn(List.of(candidate));
        when(findings.findPolicyByRunId(8L)).thenReturn(List.of(candidate));
        when(reviews.latestForRun(8L)).thenReturn(List.of(failed));
        when(reviews.latestPolicyForRun(8L)).thenReturn(List.of(failed));
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
    @Test
    void archivedOnlineEvidenceFeedsReportsWithoutAnyNetworkRequest() throws Exception {
        ScanRunService runs=mock(ScanRunService.class); ScanProjectService projects=mock(ScanProjectService.class);
        FindingPersistenceService findings=mock(FindingPersistenceService.class); AiReviewService ai=mock(AiReviewService.class);
        ScanRun run=reportRun(8L,LocalDateTime.of(2026,11,10,12,0));
        run.setNetworkMode(io.codesentry.scan.model.NetworkMode.ONLINE);
        run.setRemediationMessage("联网核实 1 项，取得可核实版本 1 项");
        run.setRemediationEvidence("{\"entries\":[{\"coordinate\":\"example:library\",\"installedVersion\":\"2.0.0\",\"cve\":\"CVE-2023-1111\",\"checkedAt\":\"2026-11-10T12:00:00Z\",\"status\":\"FOUND\",\"fixedVersions\":[\"2.0.1\"],\"verifiedTargets\":{\"2.0.1\":8},\"sources\":[\"https://osv.dev/vulnerability/GHSA-test\"]}]}");
        ScanProject project=new ScanProject(); project.setId(1L); project.setProjectName("联网报告"); project.setTargetJavaVersion(8);
        FindingView f=dependencyFinding(999L,"CVE-2023-1111","Test dependency","lib/test.jar","example:library@2.0.0");
        when(runs.requireById(8L)).thenReturn(run); when(projects.requireById(1L)).thenReturn(project);
        when(findings.findStoredByRunId(8L)).thenReturn(List.of(f));
        when(findings.findPolicyByRunId(8L)).thenReturn(List.of(f)); when(ai.latestForRun(8L)).thenReturn(List.of());
        ReportSnapshotStore snapshots=snapshotStore(); when(snapshots.read(8L)).thenReturn(List.of(f));
        RemediationReportService service=new RemediationReportService(runs,projects,findings,ai,snapshots);
        String html=new String(service.exportHtml(8L),StandardCharsets.UTF_8);
        assertTrue(html.contains("联网扫描：联网核实 1 项"));
        assertTrue(html.contains("统一候选版本：example:library:2.0.1"));
        assertTrue(html.contains("2026-11-10 20:00:00")); assertTrue(html.contains("https://osv.dev/vulnerability/GHSA-test"));
        try (org.apache.poi.xwpf.usermodel.XWPFDocument doc=new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(service.exportDocx(8L)))) {
            assertTrue(doc.getDocument().xmlText().contains("example:library:2.0.1"));
            assertTrue(doc.getDocument().xmlText().contains("联网扫描：联网核实 1 项"));
        }
    }

    @Test void pdfRetainsHtmlTablesAndStartsEverySectionOnANewPage() throws Exception {
        ScanRunService runs=mock(ScanRunService.class);ScanProjectService projects=mock(ScanProjectService.class);
        FindingPersistenceService findings=mock(FindingPersistenceService.class);AiReviewService ai=mock(AiReviewService.class);
        ScanRun run=reportRun(8L,LocalDateTime.of(2026,11,10,12,0));
        ScanProject project=new ScanProject();project.setId(1L);project.setProjectName("PDF 连续分页");project.setTargetJavaVersion(8);
        java.util.List<FindingView> members=new java.util.ArrayList<>();java.util.List<AiReviewSummary> summaries=new java.util.ArrayList<>();
        for(int i=0;i<12;i++) {
            FindingView f=dependencyFinding(500L+i,"CVE-2026-"+(90000+i),"A long component vulnerability title for pagination testing", "lib/sample.jar", "commons-io:commons-io@2.11.0");
            f.setRecommendation("升级到修复版本：2.14.0");members.add(f);
            summaries.add(aiSummary(f.getId(),"CONFIRMED",true,false,"COMPLETED"));
        }
        when(runs.requireById(8L)).thenReturn(run);when(projects.requireById(1L)).thenReturn(project);
        java.util.List<FindingView> raw=new java.util.ArrayList<>(members);
        raw.add(finding(999L,"排除项","FALSE_POSITIVE"));
        when(findings.findStoredByRunId(8L)).thenReturn(raw);
        when(findings.findPolicyByRunId(8L)).thenReturn(raw);when(ai.latestForRun(8L)).thenReturn(summaries);
        RemediationReportService service=new RemediationReportService(runs,projects,findings,ai,snapshotStore());
        try(org.apache.pdfbox.pdmodel.PDDocument pdf=org.apache.pdfbox.pdmodel.PDDocument.load(service.exportPdf(8L))) {
            assertTrue(pdf.getPage(0).getMediaBox().getWidth()>pdf.getPage(0).getMediaBox().getHeight());
            String text=new org.apache.pdfbox.text.PDFTextStripper().getText(pdf).replaceAll("\\s+", "");
            for(int i=0;i<12;i++){assertTrue(text.contains("CVE-2026-"+(90000+i)));assertTrue(text.contains("F-"+(500+i)));}
            assertTrue(text.contains("工具复核"));assertTrue(text.contains("确认风险"));
            org.apache.pdfbox.text.PDFTextStripper stripper=new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setSortByPosition(true);stripper.setStartPage(1);stripper.setEndPage(1);
            String first=stripper.getText(pdf);
            assertTrue(first.contains("扫描基线"));assertTrue(first.contains("数量说明"));
            assertFalse(first.contains("问题明细与整改建议"));
            int previous=0;
            for(String heading:List.of("问题明细与整改建议","未纳入整改的问题及原因","附录：整改版本依据")) {
                boolean found=false;
                for(int page=2;page<=pdf.getNumberOfPages();page++) {
                    stripper.setStartPage(page);stripper.setEndPage(page);
                    String content=stripper.getText(pdf).strip();
                    if(content.startsWith(heading)) {assertTrue(page>previous);previous=page;found=true;break;}
                }
                assertTrue(found,"section did not start on a fresh page: "+heading);
            }

        }
    }

    @Test
    void frontendReportUsesNodeRuntimeAndDoesNotApplyJavaUpgradeConstraints() {
        ScanRunService runs=mock(ScanRunService.class);ScanProjectService projects=mock(ScanProjectService.class);
        FindingPersistenceService findings=mock(FindingPersistenceService.class);AiReviewService reviews=mock(AiReviewService.class);
        RemediationReportService service=new RemediationReportService(runs,projects,findings,reviews,snapshotStore());
        ScanRun run=reportRun(88L,LocalDateTime.now());ScanProject project=new ScanProject();project.setId(1L);project.setProjectName("前端项目");project.setApplicationType(io.codesentry.project.model.ApplicationType.FRONTEND);project.setTargetNodeVersion("20.19.0");
        FindingView finding=dependencyFinding(303L,"TEST-NODE-VULN","测试依赖问题","package-lock.json","sample-package@1.0.0");finding.setRecommendation("本漏洞修复版本（扫描时漏洞库记录）：1.0.1。测试数据。");
        when(runs.requireById(88L)).thenReturn(run);when(projects.requireById(1L)).thenReturn(project);when(findings.findStoredByRunId(88L)).thenReturn(List.of(finding));
        when(findings.findPolicyByRunId(88L)).thenReturn(List.of(finding));when(reviews.latestForRun(88L)).thenReturn(List.of());
        when(reviews.latestPolicyForRun(88L)).thenReturn(List.of());
        String html=new String(service.exportHtml(88L),StandardCharsets.UTF_8);assertTrue(html.contains("业务 Node.js 版本：Node.js 20.19.0"));assertFalse(html.contains("业务 Java 版本："));assertFalse(html.contains("项目 Java 版本：未确认"));assertTrue(html.contains("engines.node"));assertTrue(html.contains("1.0.1"));
    }

    @Test void jarReportHighlightsRecordedArtifactInsteadOfCurrentReplacementInHtmlAndWord() throws Exception {
        ScanRunService runs=mock(ScanRunService.class);ScanProjectService projects=mock(ScanProjectService.class);
        FindingPersistenceService findings=mock(FindingPersistenceService.class);AiReviewService reviews=mock(AiReviewService.class);
        RemediationReportService service=new RemediationReportService(runs,projects,findings,reviews,snapshotStore());
        ScanRun run=reportRun(98L,LocalDateTime.now());run.setArtifactPath("/uploads/old/payment-service-1.0.jar");run.setScanRef("old.jar");
        ScanProject asset=new ScanProject();asset.setId(1L);asset.setProjectName("支付服务");asset.setSourceType(io.codesentry.project.model.ProjectSourceType.JAR_UPLOAD);asset.setLocalPath("/uploads/new/payment-service-2.0.jar");
        when(runs.requireById(98L)).thenReturn(run);when(projects.requireById(1L)).thenReturn(asset);when(findings.findStoredByRunId(98L)).thenReturn(List.of());
        when(findings.findPolicyByRunId(98L)).thenReturn(List.of());when(reviews.latestForRun(98L)).thenReturn(List.of());
        when(reviews.latestPolicyForRun(98L)).thenReturn(List.of());
        String html=new String(service.exportHtml(98L),StandardCharsets.UTF_8);
        assertTrue(html.contains("资产类型：JAR 包（上传产物）"));assertTrue(html.contains("扫描资产：支付服务"));assertTrue(html.contains("JAR 文件：payment-service-1.0.jar"));assertFalse(html.contains("payment-service-2.0.jar"));assertTrue(html.contains("color:#c83232;font-weight:700"));assertTrue(html.contains("扫描文件：payment-service-1.0.jar"));
        try (org.apache.poi.xwpf.usermodel.XWPFDocument doc=new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(service.exportDocx(98L)))) {
            var identity=doc.getParagraphs().stream().flatMap(p->p.getRuns().stream()).filter(r->r.text().equals("JAR 文件：payment-service-1.0.jar")).findFirst().orElseThrow();
            assertTrue(identity.isBold());assertEquals("C83232",identity.getColor());
        }
    }
    @Test void codeReportDistinguishesGitFromLocalAndEscapesIdentityText() {
        ScanRunService runs=mock(ScanRunService.class);ScanProjectService projects=mock(ScanProjectService.class);
        FindingPersistenceService findings=mock(FindingPersistenceService.class);AiReviewService reviews=mock(AiReviewService.class);
        RemediationReportService service=new RemediationReportService(runs,projects,findings,reviews,snapshotStore());
        ScanRun run=reportRun(99L,LocalDateTime.now());ScanProject asset=new ScanProject();asset.setId(1L);asset.setProjectName("测试<前端>");asset.setSourceType(io.codesentry.project.model.ProjectSourceType.GIT);asset.setRemoteUrl("https://fixture-user:fixture-password@example.invalid/team/web.git");
        when(runs.requireById(99L)).thenReturn(run);when(projects.requireById(1L)).thenReturn(asset);when(findings.findStoredByRunId(99L)).thenReturn(List.of());
        when(findings.findPolicyByRunId(99L)).thenReturn(List.of());when(reviews.latestForRun(99L)).thenReturn(List.of());
        when(reviews.latestPolicyForRun(99L)).thenReturn(List.of());
        String html=new String(service.exportHtml(99L),StandardCharsets.UTF_8);assertTrue(html.contains("代码（远程 Git 仓库）"));assertTrue(html.contains("https://example.invalid/team/web.git"));assertFalse(html.contains("fixture-password"));assertTrue(html.contains("测试&lt;前端&gt;"));
        asset.setSourceType(io.codesentry.project.model.ProjectSourceType.LOCAL);asset.setLocalPath("/workspace/frontend");
        html=new String(service.exportHtml(99L),StandardCharsets.UTF_8);assertTrue(html.contains("代码（本地仓库）"));assertTrue(html.contains("代码项目目录：/workspace/frontend"));
    }

}
