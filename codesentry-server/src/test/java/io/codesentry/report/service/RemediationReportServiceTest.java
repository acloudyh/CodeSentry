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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemediationReportServiceTest {

    @Test
    void reportExcludesOnlyHighConfidenceAiFalsePositivesWithoutHumanDisposition() {
        ScanRunService runs = mock(ScanRunService.class);
        ScanProjectService projects = mock(ScanProjectService.class);
        FindingPersistenceService findings = mock(FindingPersistenceService.class);
        AiReviewService aiReviews = mock(AiReviewService.class);
        RemediationReportService service = new RemediationReportService(runs, projects, findings, aiReviews);

        ScanRun run = new ScanRun();
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
        assertTrue(html.contains("OLD_AI_PROTOCOL_VISIBLE"));
        assertTrue(html.contains("DIFFERENT_AI_EVIDENCE_VISIBLE"));
        assertFalse(html.contains("误报已排除"));
        assertFalse(html.contains("默认报告已排除"));
    }

    @Test
    void reportListUsesTheSameFilteredCountsAsTheExport() {
        ScanRunService runs = mock(ScanRunService.class);
        ScanProjectService projects = mock(ScanProjectService.class);
        FindingPersistenceService findings = mock(FindingPersistenceService.class);
        AiReviewService aiReviews = mock(AiReviewService.class);
        RemediationReportService service = new RemediationReportService(runs, projects, findings, aiReviews);
        ScanRun run = new ScanRun();
        run.setId(8L); run.setProjectId(1L); run.setScanMode(ScanMode.FULL);
        run.setStatus(io.codesentry.scan.model.ScanRunStatus.COMPLETED);
        ScanProject project = new ScanProject(); project.setId(1L); project.setProjectName("review-target");
        when(runs.recent(100)).thenReturn(List.of(run));
        when(runs.requireById(8L)).thenReturn(run);
        when(projects.requireById(1L)).thenReturn(project);
        when(findings.findByRunId(8L)).thenReturn(List.of(
                finding(101L, "VISIBLE", null), finding(102L, "EXCLUDED", "FALSE_POSITIVE")));
        when(aiReviews.latestForRun(8L)).thenReturn(List.of());

        io.codesentry.report.dto.RemediationReportSummary summary = service.recent(100).get(0);
        String html = new String(service.exportHtml(8L), StandardCharsets.UTF_8);

        assertEquals(1, summary.getTotalFindings());
        assertEquals(1, summary.getNewFindings());
        assertTrue(html.contains("VISIBLE"));
        assertFalse(html.contains("EXCLUDED"));
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
