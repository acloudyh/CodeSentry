package io.codesentry.finding.service;

import io.codesentry.ai.dto.AiReviewSummary;
import io.codesentry.ai.model.AiReviewRecord;
import io.codesentry.ai.service.AiReviewService;
import io.codesentry.finding.model.FindingView;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReviewedFindingPolicyTest {
    private FindingView finding() {
        FindingView f = new FindingView(); f.setId(1L); f.setScanRunId(2L); return f;
    }
    private AiReviewSummary summary(String primaryConfidence, String verificationConfidence) {
        AiReviewSummary s = new AiReviewSummary(); s.setStatus("COMPLETED"); s.setConsolidatedVerdict("CONFIRMED");
        s.setPrimaryReview(round("PRIMARY", primaryConfidence)); s.setVerificationReview(round("VERIFY", verificationConfidence)); return s;
    }
    private AiReviewRecord round(String name, String confidence) {
        AiReviewRecord r = new AiReviewRecord(); r.setFindingId(1L); r.setScanRunId(2L); r.setReviewJobId(3L);
        r.setReviewRound(name); r.setStatus("COMPLETED"); r.setVerdict("CONFIRMED");
        r.setPromptVersion(AiReviewService.PROMPT_VERSION); r.setInputHash("evidence");
        r.setConfidence(new BigDecimal(confidence)); return r;
    }
    @Test void eachRoundMustBeStrictlyAbove50NotAverageOrRoundedValue() {
        assertTrue(ReviewedFindingPolicy.isEligible(finding(), summary("0.5001", "0.51")));
        assertTrue(ReviewedFindingPolicy.isEligible(finding(), summary("0.55", "0.60")));
        assertTrue(ReviewedFindingPolicy.exclusionReason(finding(), summary("0.50", "0.99")).contains("均必须大于 50%"));
        assertFalse(ReviewedFindingPolicy.isEligible(finding(), summary("0.50", "0.99")));
        assertFalse(ReviewedFindingPolicy.isEligible(finding(), summary("0.99", "0.50")));
        assertFalse(ReviewedFindingPolicy.isEligible(finding(), summary("0.49", "0.99")));
        assertFalse(ReviewedFindingPolicy.isEligible(finding(), summary("0.99", "0.49")));
        assertFalse(ReviewedFindingPolicy.isEligible(finding(), summary("1.01", "0.99")));
    }
    @Test void anyRoundOrHumanFalsePositiveIsExcludedEvenWithHighConfidence() {
        AiReviewSummary s = summary("0.95", "0.95");
        s.getPrimaryReview().setVerdict("FALSE_POSITIVE");
        assertFalse(ReviewedFindingPolicy.isEligible(finding(), s));
        s.getPrimaryReview().setVerdict("CONFIRMED"); s.getVerificationReview().setVerdict("FALSE_POSITIVE");
        assertFalse(ReviewedFindingPolicy.isEligible(finding(), s));
        FindingView f = finding(); f.setTriageStatus("FALSE_POSITIVE");
        assertFalse(ReviewedFindingPolicy.isEligible(f, summary("0.95", "0.95")));
    }
    @Test void failuresMissingReviewsAndLowConfidenceAreExcludedWithoutHumanConfirmation() {
        FindingView f = finding();
        assertFalse(ReviewedFindingPolicy.isEligible(f, null));
        assertFalse(ReviewedFindingPolicy.isEligible(f, summary("0.50", "0.99")));
        AiReviewSummary s = summary("0.9", "0.9"); s.setStatus("FAILED");
        assertFalse(ReviewedFindingPolicy.isEligible(f, s));
        s.setStatus("COMPLETED"); s.setVerificationReview(null);
        assertFalse(ReviewedFindingPolicy.isEligible(f, s));
        s = summary("0.9", "0.9"); s.getVerificationReview().setConfidence(null);
        assertFalse(ReviewedFindingPolicy.isEligible(f, s));
    }
    @Test void humanConfirmationOverridesMissingFailedLowConfidenceAndFalsePositiveAiReviews() {
        FindingView f = finding(); f.setTriageStatus("CONFIRMED");
        assertTrue(ReviewedFindingPolicy.isEligible(f, null));
        assertTrue(ReviewedFindingPolicy.isEligible(f, summary("0.65", "0.2")));
        AiReviewSummary failed = summary("0.9", "0.9"); failed.setStatus("FAILED");
        assertTrue(ReviewedFindingPolicy.isEligible(f, failed));
        failed.setConsolidatedVerdict("FALSE_POSITIVE");
        assertTrue(ReviewedFindingPolicy.isEligible(f, failed));
        f.setTriageStatus("FALSE_POSITIVE");
        assertFalse(ReviewedFindingPolicy.isEligible(f, failed));
    }
    @Test void versionHashBatchAndJobMustMatch() {
        AiReviewSummary s = summary("0.9", "0.9"); s.getVerificationReview().setInputHash("other");
        assertFalse(ReviewedFindingPolicy.isEligible(finding(), s));
        s = summary("0.9", "0.9"); s.getVerificationReview().setPromptVersion("old");
        assertFalse(ReviewedFindingPolicy.isEligible(finding(), s));
        s = summary("0.9", "0.9"); s.getVerificationReview().setReviewJobId(4L);
        assertFalse(ReviewedFindingPolicy.isEligible(finding(), s));
        s = summary("0.9", "0.9"); s.getVerificationReview().setScanRunId(3L);
        assertFalse(ReviewedFindingPolicy.isEligible(finding(), s));
    }
    @Test void selectionIsReadOnlyAndKeepsConditionalEvidenceWithoutCallingItConfirmed() {
        FindingView f = finding(); AiReviewSummary s = summary("0.7", "0.8");
        s.setStatus("NEEDS_HUMAN"); s.setConsolidatedVerdict("NEEDS_HUMAN");
        s.getVerificationReview().setVerdict("CONDITIONAL");
        List<FindingView> raw = new ArrayList<>(List.of(f));
        assertEquals(List.of(f), ReviewedFindingPolicy.select(raw, Map.of(1L, s)));
        assertEquals("CONDITIONAL", s.getVerificationReview().getVerdict());
        assertNull(f.getTriageStatus()); assertEquals(1, raw.size());
        assertTrue(ReviewedFindingPolicy.select(raw, Map.of()).isEmpty()); assertEquals(1, raw.size());
    }
}
