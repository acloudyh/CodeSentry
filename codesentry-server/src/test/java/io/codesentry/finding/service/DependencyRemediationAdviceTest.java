package io.codesentry.finding.service;

import io.codesentry.finding.model.FindingView;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DependencyRemediationAdviceTest {
    @Test void legacyDom4jHasCoordinatesVersionAndCodeRemediation() {
        for (String cve : new String[]{"CVE-2020-10683", "CVE-2018-1000632"}) {
            String advice = DependencyRemediationAdvice.fromScan("dom4j:dom4j", "1.6.1", cve, "", "");
            assertTrue(advice.contains("org.dom4j:dom4j:2.1.4"));
            assertTrue(advice.contains("2.1.3"));
            assertTrue(advice.contains("SAXReader.createDefault()"));
            assertTrue(advice.contains("dom4j-1.6.1.jar"));
            assertTrue(advice.contains("https://github.com/dom4j/dom4j/releases/tag/version-2.1.3"));
        }
    }
    @Test void unsupportedLog4jDoesNotSuggestUpgradingWithinOneXEvenIfScannerSaysTwoZero() {
        String advice = DependencyRemediationAdvice.fromScan("log4j:log4j", "1.2.17", "CVE-2023-26464", "2.0", "");
        assertTrue(advice.contains("没有可继续使用的官方 1.x 修复版本"));
        assertTrue(advice.contains("log4j-api:2.26.1"));
        assertTrue(advice.contains("log4j-core:2.26.1"));
        assertTrue(advice.contains("log4j-1.2-api"));
        assertTrue(advice.contains("不能与旧 log4j JAR 共存"));
    }
    @Test void keepsEveryScannerBranchWithoutClaimingAllVulnerabilitiesAreFixed() {
        String advice = DependencyRemediationAdvice.fromScan("ch.qos.logback:logback-core", "1.1.3", "CVE-2023-6378", "1.3.12, 1.4.12, 1.2.13", "https://example.org/advisory");
        assertTrue(advice.contains("1.3.12, 1.4.12, 1.2.13"));
        assertTrue(advice.contains("不表示随意选一个就能解决全部漏洞"));
        assertTrue(advice.contains("https://example.org/advisory"));
    }
    @Test void missingFixIsNotConfusedWithNoRiskOrAFabricatedTarget() {
        String advice = DependencyRemediationAdvice.fromScan("example:library", "1.0", "CVE-2026-9999", "", "");
        assertTrue(advice.contains("扫描结果未给出，需核实官方修复方案"));
        assertTrue(advice.contains("https://nvd.nist.gov/vuln/detail/CVE-2026-9999"));
        assertFalse(advice.contains("请查阅漏洞公告"));
        assertFalse(advice.contains("2.1.4"));
    }
    @Test void enrichesOldArchivedPlaceholderWithoutChangingSourceFinding() {
        FindingView f = finding("dom4j:dom4j@1.6.1", "CVE-2020-10683", "升级到修复版本：请查阅漏洞公告");
        assertTrue(DependencyRemediationAdvice.forFinding(f).contains("org.dom4j:dom4j:2.1.4"));
        assertEquals("升级到修复版本：请查阅漏洞公告", f.getRecommendation());
        f = finding("org.apache.poi:poi@3.15", "CVE-2019-12415", "升级到修复版本：4.1.1");
        assertTrue(DependencyRemediationAdvice.forFinding(f).contains("4.1.1"));
    }
    @Test void preservesExplicitAdviceAndDoesNotDowngradeNewerDom4jOrGuessUnknownCves() {
        FindingView f = finding("dom4j:dom4j@1.6.1", "CVE-2020-10683", "维护人指定：先做兼容验证");
        assertEquals(f.getRecommendation(), DependencyRemediationAdvice.forFinding(f));
        assertFalse(DependencyRemediationAdvice.fromScan("org.dom4j:dom4j", "2.2.0", "CVE-2020-10683", "", "").contains("建议升级目标"));
        assertFalse(DependencyRemediationAdvice.fromScan("dom4j:dom4j", "1.6.1", "CVE-2099-9999", "", "").contains("建议升级目标"));
    }
    @Test void compactReportKeepsBothVersionFactsButSharesHandlingAndMovesUrls() {
        FindingView a = finding("ch.qos.logback:logback-classic@1.1.3", "CVE-2017-5929", "升级到修复版本：1.2.0");
        FindingView b = finding("ch.qos.logback:logback-classic@1.1.3", "CVE-2023-6378", "升级到修复版本：1.3.12, 1.4.12, 1.2.13");
        String compact = DependencyRemediationAdvice.compact(java.util.List.of(a, b));
        assertTrue(compact.contains("CVE-2017-5929：1.2.0"));
        assertTrue(compact.contains("CVE-2023-6378：1.3.12, 1.4.12, 1.2.13"));
        assertEquals(1, compact.split("处理：", -1).length - 1);
        assertEquals(1, compact.split("验证：", -1).length - 1);
        assertFalse(compact.contains("https://"));
        assertFalse(compact.contains("当前组件"));
        assertTrue(DependencyRemediationAdvice.references(a).contains("https://nvd.nist.gov/vuln/detail/CVE-2017-5929"));
    }
    @Test void oldVerboseSnapshotIsCondensedAndUnknownFixesAreNotLostInMixedGroup() {
        FindingView a = finding("example:lib@1", "CVE-2026-1234",
                DependencyRemediationAdvice.fromScan("example:lib", "1", "CVE-2026-1234", "2.0", "https://example.org/fix"));
        FindingView b = finding("example:lib@1", "CVE-2026-2345", "升级到修复版本：请查阅漏洞公告");
        String compact = DependencyRemediationAdvice.compact(java.util.List.of(a, b));
        assertTrue(compact.contains("CVE-2026-1234：2.0"));
        assertTrue(compact.contains("修复版本待核实：CVE-2026-2345"));
        assertFalse(compact.contains("https://"));
    }
    @Test void axisOfficialPatchIsDistinguishedFromAReleasedFixedVersion() {
        FindingView a = finding("axis:axis@1.4", "CVE-2023-40743", "升级到修复版本：请查阅漏洞公告");
        FindingView b = finding("axis:axis@1.4", "CVE-2019-0227", "升级到修复版本：请查阅漏洞公告");
        String compact = DependencyRemediationAdvice.compact(java.util.List.of(a, b));
        assertTrue(compact.contains("未计划发布 CVE-2023-40743 的修复版"));
        assertTrue(compact.contains("不能只换 JAR"));
        assertTrue(compact.contains("官方补丁处理该漏洞"));
        assertFalse(compact.contains("漏洞库未提供"));
        assertFalse(compact.contains("https://"));
        assertTrue(DependencyRemediationAdvice.references(a).stream().anyMatch(url -> url.contains("7e667534")));
        String ledger = DependencyRemediationAdvice.fromScan("axis:axis", "1.4", "CVE-2023-40743", "", "");
        assertTrue(ledger.contains("ServiceFactory.getService"));
        assertTrue(ledger.contains("未计划发布本漏洞的 1.x 修复版"));
    }
    @Test void standaloneJarAdviceStaysConciseAndRetainsTargetAndValidation() {
        FindingView f = finding("commons-collections:commons-collections@3.2.1", "CVE-2015-7501", "升级到修复版本：3.2.2");
        f.setFilePath("app.jar/BOOT-INF/lib/commons-collections-3.2.1.jar");
        String advice = DependencyRemediationAdvice.compact(java.util.List.of(f), 8);
        assertTrue(advice.contains("候选版本：3.2.2"));
        assertTrue(advice.contains("pom.xml"));
        assertTrue(advice.length() < 350);
        assertTrue(advice.contains("dependencyManagement"));
        assertTrue(advice.contains("重新打包"));
        assertTrue(advice.contains("Java 8"));
        assertTrue(advice.contains("勿开启危险反序列化"));
        assertTrue(advice.contains("上传新包全量复扫"));
        assertFalse(advice.contains("组件记录位于外层包"));
    }
    @Test void nestedNettyDoesNotRecommendOnlyOverridingBusinessDependency() {
        FindingView f = finding("io.netty:netty-handler@4.1.48.Final", "CVE-2023-34462", "升级到修复版本：4.1.94.Final");
        f.setFilePath("app.jar/BOOT-INF/lib/nacos-client-2.2.0.jar");
        String advice = DependencyRemediationAdvice.compact(java.util.List.of(f), 8);
        assertTrue(advice.contains("外层包 nacos-client-2.2.0.jar"));
        assertTrue(advice.length() < 350);
        assertTrue(advice.contains("仅修改业务侧依赖版本无效"));
        assertTrue(advice.contains("需升级或重建外层包"));
        assertTrue(advice.contains("TLS 握手"));
        assertFalse(advice.contains("若为直接依赖"));
    }
    @Test void incompleteHutoolFixesRemainUnresolvedRatherThanAnInventedUnifiedTarget() {
        FindingView a = finding("cn.hutool:hutool-json@5.3.8", "CVE-2022-45688", "升级到修复版本：5.8.25");
        FindingView b = finding("cn.hutool:hutool-json@5.3.8", "CVE-2023-42276", "升级到修复版本：请查阅漏洞公告");
        String advice = DependencyRemediationAdvice.compact(java.util.List.of(a, b), 8);
        assertTrue(advice.contains("CVE-2023-42276"));
        assertTrue(advice.contains("不能视为全部修复"));
        assertTrue(advice.contains("hutool-*"));
        assertTrue(advice.contains("JSON 解析及异常输入"));
        assertFalse(advice.contains("候选版本："));
    }
    @Test void historicalFixRecordsDoNotRecommendDowngradingAnAlreadyNewerVersion() {
        FindingView f = finding("io.netty:netty-handler@4.1.101.Final", "CVE-2023-34462", "升级到修复版本：4.1.94.Final");
        String advice = DependencyRemediationAdvice.compact(java.util.List.of(f), 8);
        assertTrue(advice.contains("CVE-2023-34462：4.1.94.Final"));
        assertFalse(advice.contains("候选版本："));
    }
    private FindingView finding(String signature, String cve, String recommendation) {
        FindingView f = new FindingView(); f.setScanner("Trivy"); f.setCategory("VULNERABILITY");
        f.setCodeSnippet(signature); f.setRuleId(cve); f.setRecommendation(recommendation); return f;
    }
}
