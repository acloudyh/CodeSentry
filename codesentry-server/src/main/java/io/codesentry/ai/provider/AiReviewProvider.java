package io.codesentry.ai.provider;

import io.codesentry.ai.dto.AiReviewDecision;
import io.codesentry.ai.dto.AiReviewEvidence;

public interface AiReviewProvider {
    AiReviewDecision review(AiReviewEvidence evidence, String reviewRound, AiReviewDecision primaryDecision);
    /** Freeze configuration for both review rounds and every worker in one job. */
    default AiReviewProvider snapshot() { return this; }
    String providerName();
    String modelName();
}
