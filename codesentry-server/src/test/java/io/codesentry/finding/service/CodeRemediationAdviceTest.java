package io.codesentry.finding.service;

import io.codesentry.finding.model.FindingView;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodeRemediationAdviceTest {
    @Test void suppliesActionAndVerificationForRulesMissingScannerFixes() {
        String[][] cases = {
            {"javascript.lang.security.audit.detect-non-literal-regexp.detect-non-literal-regexp", "includes/indexOf"},
            {"generic.secrets.security.detected-jwt-token.detected-jwt-token", "短期令牌"},
            {"java.lang.security.audit.permissive-cors.permissive-cors", "Origin"},
            {"javascript.lang.security.audit.prototype-pollution.prototype-pollution-loop", "__proto__"},
            {"javascript.browser.security.eval-detected.eval-detected", "JSON.parse"},
            {"javascript.lang.security.detect-insecure-websocket", "HTTPS/WSS"}
        };
        for (String[] entry : cases) {
            FindingView f = new FindingView(); f.setScanner("Semgrep"); f.setCategory("security"); f.setRuleId(entry[0]);
            f.setRecommendation("请结合代码上下文复核并按安全编码规范整改。");
            String advice = DependencyRemediationAdvice.compact(java.util.List.of(f));
            assertTrue(advice.contains(entry[1]), entry[0]); assertTrue(advice.contains("处理：")); assertTrue(advice.contains("验证："));
            assertEquals("请结合代码上下文复核并按安全编码规范整改。", f.getRecommendation());
        }
    }
    @Test void preservesSpecificAdviceAndMarksUnknownRulesAsRequiringManualWork() {
        FindingView f = new FindingView(); f.setRuleId("custom-rule"); f.setRecommendation("维护人确认：移除旧接口。");
        assertEquals(f.getRecommendation(), CodeRemediationAdvice.forFinding(f));
        f.setRecommendation(null); assertTrue(CodeRemediationAdvice.forFinding(f).contains("尚无专用修复模板"));
    }
}
