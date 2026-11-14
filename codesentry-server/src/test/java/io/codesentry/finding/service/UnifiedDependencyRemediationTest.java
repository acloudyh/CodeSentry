package io.codesentry.finding.service;

import static org.junit.jupiter.api.Assertions.*;

import io.codesentry.finding.model.FindingView;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnifiedDependencyRemediationTest {
  private FindingView finding(String signature, String cve, String fixed) {
    FindingView f = new FindingView();
    f.setScanner("Trivy");
    f.setCategory("VULNERABILITY");
    f.setCodeSnippet(signature);
    f.setRuleId(cve);
    f.setFilePath("app.jar/BOOT-INF/lib/" + signature.split(":")[1].replace('@', '-') + ".jar");
    f.setRecommendation("升级到修复版本：" + (fixed.isEmpty() ? "请查阅漏洞公告" : fixed));
    return f;
  }

  @Test
  void selectsOneJava8TomcatReleaseCoveringAllMemberFixesAndPreservesSource() {
    FindingView a =
        finding(
            "org.apache.tomcat.embed:tomcat-embed-core@9.0.91",
            "CVE-2026-1",
            "11.0.25, 10.1.58, 9.0.121");
    FindingView b =
        finding(
            "org.apache.tomcat.embed:tomcat-embed-core@9.0.91",
            "CVE-2026-2",
            "11.0.22, 10.1.55, 9.0.118");
    String before = a.getRecommendation();
    String body = DependencyRemediationAdvice.compact(List.of(a, b), 8);
    assertTrue(body.contains("统一推荐版本：org.apache.tomcat.embed:tomcat-embed-core:9.0.123"));
    assertTrue(body.contains("2 / 2 条"));
    assertFalse(body.contains("10.1.58"));
    assertFalse(body.contains("11.0.25"));
    assertEquals(before, a.getRecommendation());
    assertTrue(
        String.join("\n", DependencyRemediationAdvice.versionRecords(List.of(a, b)))
            .contains("11.0.25, 10.1.58, 9.0.121"));
  }

  @Test
  void chosenProjectJavaControlsTheResultInsteadOfAssumingJava8OrUpgradingTheRuntime() {
    FindingView f =
        finding(
            "org.apache.tomcat.embed:tomcat-embed-core@9.0.91", "CVE-2026-1", "10.1.58, 11.0.25");
    assertTrue(DependencyRemediationAdvice.compact(List.of(f), 8).contains("统一推荐版本：尚未核定"));
    assertTrue(
        DependencyRemediationAdvice.compact(List.of(f), 11)
            .contains("统一推荐版本：org.apache.tomcat.embed:tomcat-embed-core:10.1.58"));
    assertFalse(DependencyRemediationAdvice.compact(List.of(f), 11).contains("core:11.0.25"));
    assertFalse(DependencyRemediationAdvice.compact(List.of(f), 17).contains("项目约束："));
    assertFalse(DependencyRemediationAdvice.compact(List.of(f), 8).contains("升级 Java"));
  }

  @Test
  void parallelBranchBackportCannotBeUsedAsProofForANewerUnfixedBranch() {
    FindingView a =
        finding("com.fasterxml.jackson.core:jackson-databind@2.13.3", "CVE-A", "2.18.11, 2.21.7");
    FindingView b =
        finding("com.fasterxml.jackson.core:jackson-databind@2.13.3", "CVE-B", "2.21.6");
    String body = DependencyRemediationAdvice.compact(List.of(a, b), 8);
    assertTrue(body.contains("jackson-databind:2.21.7"));
    assertTrue(body.contains("2 / 2 条"));
    assertFalse(body.contains("jackson-databind:2.18.11"));
  }

  @Test
  void missingOneFixCannotTurnPartialCoverageIntoAGroupWideUpgrade() {
    FindingView a = finding("commons-io:commons-io@2.11.0", "CVE-A", "2.14.0");
    FindingView b = finding("commons-io:commons-io@2.11.0", "CVE-B", "");
    String body = DependencyRemediationAdvice.compact(List.of(a, b), 8);
    assertTrue(body.contains("统一推荐版本：尚未核定覆盖整组"));
    assertTrue(body.contains("1 / 2 条"));
    assertTrue(body.contains("尚待解决：CVE-B"));
    assertTrue(body.contains("临时"));
  }

  @Test
  void springBackportsAllowJava8AndStateEnterpriseAccessAndNonVersionActions() {
    FindingView web = finding("org.springframework:spring-web@5.3.22", "CVE-2024-38820", "6.1.14");
    FindingView invoker =
        finding("org.springframework:spring-web@5.3.22", "CVE-2016-1000027", "6.0.0");
    String body = DependencyRemediationAdvice.compact(List.of(web, invoker), 8);
    assertTrue(body.contains("spring-web:5.3.50"));
    assertTrue(body.contains("企业支持版本"));
    assertTrue(body.contains("另 1 条须移除风险入口"));
    assertTrue(body.contains("HttpInvokerServiceExporter"));
    assertFalse(body.contains("spring-web:6."));
  }

  @Test
  void unknownJavaOrUnknownCompatibilityDoesNotProduceAnUnverifiedTarget() {
    FindingView f = finding("commons-io:commons-io@2.11.0", "CVE-A", "2.14.0");
    assertTrue(DependencyRemediationAdvice.compact(List.of(f), null).contains("Java 版本：未确认"));
    assertFalse(
        DependencyRemediationAdvice.compact(List.of(f), null).contains("commons-io:2.14.0"));
    f.setCodeSnippet("example:unknown@1.0");
    assertTrue(DependencyRemediationAdvice.compact(List.of(f), 8).contains("统一推荐版本：尚未核定"));
  }

  @Test
  void rangeAndPrereleaseFactsAreKeptForReferenceButCannotBecomeStableTargets() {
    FindingView f = finding("commons-io:commons-io@2.11.0", "CVE-A", ">=2.14.0, 3.0.0-RC1");
    assertTrue(DependencyRemediationAdvice.compact(List.of(f), 8).contains("统一推荐版本：尚未核定"));
    assertTrue(
        String.join("\n", DependencyRemediationAdvice.versionRecords(List.of(f)))
            .contains(">=2.14.0, 3.0.0-RC1"));
  }

  @Test
  void numericOrderingDoesNotComparePatchVersionsLexicallyOrRecommendDowngrades() {
    FindingView a = finding("commons-beanutils:commons-beanutils@1.8.0", "CVE-A", "1.9.4");
    FindingView b = finding("commons-beanutils:commons-beanutils@1.8.0", "CVE-B", "1.11.0");
    assertTrue(
        DependencyRemediationAdvice.compact(List.of(a, b), 8).contains("commons-beanutils:1.11.0"));
    a.setCodeSnippet("commons-beanutils:commons-beanutils@1.12.0");
    b.setCodeSnippet(a.getCodeSnippet());
    String body = DependencyRemediationAdvice.compact(List.of(a, b), 8);
    assertTrue(body.contains("commons-beanutils:1.12.0"));
    assertTrue(body.contains("不能建议降级"));
  }

  @Test
  void guavaKeepsTheJreFlavorAndOnlyUsesAuditedCrossFlavorFixes() {
    FindingView a = finding("com.google.guava:guava@30.1-jre", "CVE-2023-2976", "32.0.0-android");
    FindingView b = finding("com.google.guava:guava@30.1-jre", "CVE-2026-102554", "33.7.2");
    String body = DependencyRemediationAdvice.compact(List.of(a, b), 8);
    assertTrue(body.contains("guava:33.8.0-jre"));
    assertTrue(body.contains("2 / 2 条"));
    a.setRuleId("CVE-UNKNOWN");
    assertTrue(DependencyRemediationAdvice.compact(List.of(a, b), 8).contains("尚待解决：CVE-UNKNOWN"));
  }

  @Test
  void aJava8BackportRecipeDoesNotRecommendAnUnverifiedNewRuntime() {
    FindingView f = finding("net.sf.json-lib:json-lib@2.4", "CVE-2024-47855", "");
    String body = DependencyRemediationAdvice.compact(List.of(f), 8);
    assertTrue(body.contains("回移官方 JSONTokener"));
    assertFalse(body.contains("迁移候选"));
    assertFalse(body.contains("升级 Java"));
  }

  @Test
  void aNewerProjectJdkCannotBeAssignedAnOlderBranchBeyondItsVerifiedSupportRange() {
    FindingView f = finding("org.springframework:spring-web@5.3.22", "CVE-2024-38820", "6.1.14");
    String body = DependencyRemediationAdvice.compact(List.of(f), 25);
    assertFalse(body.contains("项目约束："));
    assertTrue(body.contains("统一推荐版本：尚未核定"));
    assertFalse(body.contains("spring-web:5.3.50"));
    assertFalse(body.contains("spring-web:6.1.14"));
  }

  @Test
  void hutoolUpstreamPatchCompletesTheGroupWithoutChangingHistoricalScannerEvidence() {
    FindingView a = finding("cn.hutool:hutool-core@5.8.20", "CVE-2023-42276", "");
    FindingView b = finding("cn.hutool:hutool-core@5.8.20", "CVE-2023-42277", "");
    FindingView c = finding("cn.hutool:hutool-core@5.8.20", "CVE-2023-42278", "5.8.22");
    FindingView d = finding("cn.hutool:hutool-core@5.8.20", "CVE-2023-51075", "5.8.24");
    String before = a.getRecommendation();
    String body = DependencyRemediationAdvice.compact(List.of(a, b, c, d), 8);
    assertTrue(body.contains("统一推荐版本：cn.hutool:hutool-core:5.8.24"));
    assertTrue(body.contains("4 / 4 条"));
    assertFalse(body.contains("尚待解决"));
    assertFalse(body.contains("项目约束："));
    assertEquals(before, a.getRecommendation());
    String records = String.join("\n", DependencyRemediationAdvice.versionRecords(List.of(a, b)));
    assertTrue(records.contains("未记录明确修复版本（扫描时漏洞库记录）"));
    assertTrue(records.contains("官方补充 CVE-2023-42277：5.8.22"));
    assertTrue(DependencyRemediationAdvice.compact(List.of(a), 8).contains("1 / 1 条"));
  }
}
