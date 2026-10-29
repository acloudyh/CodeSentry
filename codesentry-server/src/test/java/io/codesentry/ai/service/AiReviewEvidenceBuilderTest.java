package io.codesentry.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.ai.dto.AiReviewEvidence;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.finding.model.FindingView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiReviewEvidenceBuilderTest {
    private final AiReviewEvidenceBuilder builder = new AiReviewEvidenceBuilder(
            new CodeSentryProperties(), new ObjectMapper());

    @Test
    void secretFindingIncludesRealSourceAndUsesRepositoryRelativePath() {
        FindingView finding = finding("Gitleaks", "generic-api-key",
                "/Users/test/.codesentry/snapshots/7/current/project/src/config.properties",
                "api_key=very-secret-value");

        AiReviewEvidence evidence = builder.build(finding);

        assertEquals("api_key=very-secret-value", evidence.getCodeSnippet());
        assertEquals("project/src/config.properties", evidence.getFilePath());
        assertTrue(builder.serialize(evidence).contains("very-secret-value"));
    }

    @Test
    void codeFindingKeepsOriginalSourceForReview() {
        FindingView finding = finding("Semgrep", "command-execution", "src/App.java",
                "Authorization: Bearer abc.def\npassword=hunter2\nhttps://x.test?a=1&token=sensitive");

        String snippet = builder.build(finding).getCodeSnippet();

        assertEquals(finding.getCodeSnippet(), snippet);
    }

    private FindingView finding(String scanner, String ruleId, String path, String snippet) {
        FindingView finding = new FindingView();
        finding.setId(1L);
        finding.setScanRunId(7L);
        finding.setScanner(scanner);
        finding.setRuleId(ruleId);
        finding.setSeverity("HIGH");
        finding.setTitle("test");
        finding.setFilePath(path);
        finding.setStartLine(10);
        finding.setCodeSnippet(snippet);
        return finding;
    }
}
