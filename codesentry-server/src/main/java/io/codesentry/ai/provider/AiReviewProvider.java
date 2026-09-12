package io.codesentry.ai.provider;

import io.codesentry.ai.dto.AiReviewDecision;
import io.codesentry.ai.dto.AiReviewEvidence;

public interface AiReviewProvider {
    AiReviewDecision review(AiReviewEvidence evidence, String reviewRound, AiReviewDecision primaryDecision);
    String providerName();
    String modelName();
}
