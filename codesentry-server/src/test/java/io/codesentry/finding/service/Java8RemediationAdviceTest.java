package io.codesentry.finding.service;
import io.codesentry.finding.model.FindingView;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class Java8RemediationAdviceTest {
    FindingView finding(String signature, String cve) {
        FindingView f = new FindingView();f.setScanner("Trivy");f.setCategory("VULNERABILITY");
        f.setCodeSnippet(signature);f.setRuleId(cve);f.setRecommendation("升级到修复版本：请查阅漏洞公告");return f;
    }
    @Test void choosesJava8PoiCandidateWithoutPromotingTheJava17Release() {
        FindingView f = finding("org.apache.poi:poi@3.7", "CVE-2019-12415");
        String advice = DependencyRemediationAdvice.compact(List.of(f), 8);
        assertTrue(advice.contains("统一 POI 组件至 5.5.1"));
        assertTrue(advice.contains("当前：org.apache.poi:poi 3.7（Java 8）"));
        assertTrue(advice.contains("重新编译、适配"));
        assertFalse(DependencyRemediationAdvice.compact(List.of(f), null).contains("统一 POI 组件至 5.5.1"));
        assertNull(Java8RemediationAdvice.recommend(List.of(f), 17));
    }
    @Test void doesNotCallTheOldLogbackJava8BranchAFixForNewCves() {
        String advice = DependencyRemediationAdvice.compact(List.of(
                finding("ch.qos.logback:logback-core@1.1.3", "CVE-2026-10532")),8);
        assertTrue(advice.contains("不能以 1.3.16 视为本组全部漏洞已修复"));
        assertTrue(advice.contains("Log4j 2.26.1"));
        assertTrue(advice.contains("重建"));
        assertTrue(advice.contains("log4j-slf4j-impl"));
        assertFalse(advice.contains("建议目标：Logback 1.6"));
    }
    @Test void removesGuessworkForAntAndDifferentiatesHttpClientMigrationFromUpgrade() {
        assertTrue(DependencyRemediationAdvice.compact(List.of(finding("org.apache.ant:ant@1.7.1", "CVE-2021-36373")),8).contains("ant:1.10.18"));
        String http = DependencyRemediationAdvice.compact(List.of(finding("org.apache.httpcomponents:httpclient@4.5.9", "CVE-2020-13956")),8);
        assertTrue(http.contains("httpclient:4.5.14"));assertTrue(http.contains("重建原包"));
        String commons = DependencyRemediationAdvice.compact(List.of(finding("commons-httpclient:commons-httpclient@3.1", "CVE-2012-5783")),8);
        assertTrue(commons.contains("旧 API 调用"));assertTrue(commons.contains("不能直接更名换包"));
    }
    @Test void alignsXmlbeansToPoiAndKeepsAxisAsAnExplicitUnresolvedMigration() {
        String xml = DependencyRemediationAdvice.compact(List.of(finding("org.apache.xmlbeans:xmlbeans@2.6.0","CVE-2021-23926")),8);
        assertTrue(xml.contains("xmlbeans:5.3.0"));assertTrue(xml.contains("POI 5.5.1"));
        String axis = DependencyRemediationAdvice.compact(List.of(finding("axis:axis@1.4","CVE-2023-40743")),8);
        assertTrue(axis.contains("尚未核定覆盖全部所列漏洞的 Java 8 替换版本"));
        assertTrue(axis.contains("该补丁不覆盖其余漏洞"));
    }
    @Test void newOrUnknownCvesDoNotInheritAnUnverifiedCandidate() {
        FindingView unknown = finding("org.apache.poi:poi@3.7", "CVE-2099-12345");
        assertNull(Java8RemediationAdvice.recommend(List.of(unknown),8));
        assertFalse(DependencyRemediationAdvice.compact(List.of(unknown),8).contains("5.5.1"));
    }
    @Test void codeAdviceSpecifiesTheEditInsteadOfTheGenericPlaceholder() {
        FindingView f = new FindingView();f.setScanner("Semgrep");f.setRuleId("html.security.audit.missing-integrity.missing-integrity");
        String advice = DependencyRemediationAdvice.compact(List.of(f),8);
        assertTrue(advice.contains("SHA-384 integrity"));assertTrue(advice.contains("crossorigin=anonymous"));
        assertTrue(advice.contains("示例页从发布包移除"));assertFalse(advice.contains("按安全编码规范"));
    }
    @Test void suspectedSecretsRequireConfirmationAndRevocationNotJustDeletingALine() {
        FindingView f = new FindingView();f.setScanner("Trivy");f.setRuleId("private-key");
        String advice = DependencyRemediationAdvice.compact(List.of(f),8);
        assertTrue(advice.contains("确认是否为有效业务凭据"));assertTrue(advice.contains("旧凭据已撤销或失效"));
    }
}
