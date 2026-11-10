package io.codesentry.finding.service;

import io.codesentry.finding.model.FindingView;
import java.util.List;

/** Upstream remedies checked 2026-11-10. Render-only supplements for known missing scan facts. */
final class VerifiedDependencyRemediation {
    private VerifiedDependencyRemediation() { }

    static String advice(FindingView finding, Integer javaVersion) {
        String key = key(finding);
        String advice;
        switch (key) {
            case "com.alibaba:fastjson@1.2.83|CVE-2026-16723":
                advice = "建议目标：com.alibaba:fastjson:1.2.84（官方修复版本，CVE-2026-16723）。\n"
                        + "处理：更新依赖并移除旧 fastjson-1.2.83.jar，重新打包；暂不能升级时启用 -Dfastjson.parser.safeMode=true。\n"
                        + "验证：回归 JSON 序列化、反序列化及异常输入处理，上传新包全量复扫。";
                break;
            case "commons-configuration:commons-configuration@1.8|CVE-2025-46392":
                advice = "迁移目标：org.apache.commons:commons-configuration2:2.15.1；1.x 无本漏洞的官方修复版。\n"
                        + "处理：将 org.apache.commons.configuration 调用迁移至 configuration2，按官方迁移指南适配配置构建器；移除旧 1.8 包，不能只替换 JAR。\n"
                        + "验证：回归配置加载、插值及异常配置处理，重新打包复扫。";
                break;
            case "commons-lang:commons-lang@2.6|CVE-2025-48924":
                advice = "迁移目标：org.apache.commons:commons-lang3:3.18.0（本漏洞修复起点）。\n"
                        + "处理：将 org.apache.commons.lang 调用改为 lang3 并适配 API，移除旧 commons-lang-2.6.jar；传递引入方也须升级。\n"
                        + "验证：回归字符串、反射及超长类名输入处理，重新打包复扫。";
                break;
            case "mysql:mysql-connector-java@8.0.30|CVE-2023-22102":
                advice = "建议目标：com.mysql:mysql-connector-j:8.2.0（本漏洞修复起点，已更换 Maven 坐标）。\n"
                        + "处理：替换旧 mysql:mysql-connector-java 依赖并清除旧驱动，核对数据库服务端兼容性。\n"
                        + "验证：回归数据库认证、TLS、连接池和事务，重新打包复扫。";
                break;
            case "org.springframework:spring-expression@5.3.22|CVE-2026-41849":
                advice = "官方修复：CVE-2026-41849 的 5.3.x 修复起点为 5.3.49（仅企业支持提供）。\n"
                        + "处理：Java 8 项目通过企业支持获取覆盖本组漏洞的补丁并统一 Spring 模块；暂不能升级时停止解析用户可控 SpEL。\n"
                        + "验证：回归表达式和异常输入处理，复扫全部所列 CVE；不能仅凭本条版本认定整组已修复。";
                break;
            case "org.bouncycastle:bcprov-jdk15on@1.69|CVE-2023-33201":
                advice = "官方修复：CVE-2023-33201 在 BC 1.74 修复。\n"
                        + "处理：迁移至受维护的 bcprov-jdk18on 系列，与 bcpkix、bcutil 统一版本并满足本组其余修复记录，移除旧 jdk15on 包。\n"
                        + "验证：回归证书、LDAP 查询和加解密，重新打包复扫。";
                break;
            case "net.sf.json-lib:json-lib@2.4|CVE-2024-47855":
                advice = "修复方案：在项目现有 Java 版本下回移官方 JSONTokener 的不闭合注释修复补丁。\n"
                        + "处理：修改注释结束及异常输入处理逻辑，重新构建并替换旧 2.4 包；替代实现须先核实项目 JDK 兼容性。\n"
                        + "验证：回归 JSON/XML 转换和不闭合注释输入，重新打包复扫。";
                break;
            case "com.lowagie:itext@2.1.7|CVE-2017-9096":
                advice = "迁移候选：com.itextpdf:itextpdf:5.5.13.4。\n"
                        + "处理：将 com.lowagie 调用适配为 com.itextpdf，核对授权要求并移除旧 2.1.7 包；XML 解析禁用外部实体及外部 DTD。\n"
                        + "验证：回归 PDF 生成、表单及恶意 XML 拒绝行为，重新打包复扫。";
                break;
            default: return null;
        }
        return advice + (javaVersion == null ? "" : "\n兼容要求：保持 Java " + javaVersion + " 兼容。");
    }

    static List<String> references(FindingView finding) {
        switch (key(finding)) {
            case "com.alibaba:fastjson@1.2.83|CVE-2026-16723":
                return List.of("https://github.com/alibaba/fastjson/releases/tag/1.2.84",
                        "https://github.com/alibaba/fastjson2/wiki/Security-Advisory:-Remote-Code-Execution-in-fastjson-1.2.68%E2%80%931.2.83");
            case "commons-configuration:commons-configuration@1.8|CVE-2025-46392":
                return List.of("https://lists.apache.org/thread/y1pl0mn3opz6kwkm873zshjdxq3dwq5s",
                        "https://commons.apache.org/proper/commons-configuration/userguide/upgradeto2_0.html",
                        "https://commons.apache.org/proper/commons-configuration/changes.html");
            case "commons-lang:commons-lang@2.6|CVE-2025-48924":
                return List.of("https://commons.apache.org/proper/commons-lang/changes.html");
            case "mysql:mysql-connector-java@8.0.30|CVE-2023-22102":
                return List.of("https://www.oracle.com/security-alerts/cpuoct2023.html",
                        "https://dev.mysql.com/doc/relnotes/connector-j/en/news-8-2-0.html");
            case "org.springframework:spring-expression@5.3.22|CVE-2026-41849":
                return List.of("https://spring.io/security/cve-2026-41849/");
            case "org.bouncycastle:bcprov-jdk15on@1.69|CVE-2023-33201":
                return List.of("https://www.bouncycastle.org/resources/java-release-1-74-is-now-available-for-download/");
            case "net.sf.json-lib:json-lib@2.4|CVE-2024-47855":
                return List.of("https://github.com/kordamp/json-lib/releases/tag/v3.1.0",
                        "https://github.com/kordamp/json-lib/commit/a0c4a0eae277130e22979cf307c95dec4005a78e");
            case "com.lowagie:itext@2.1.7|CVE-2017-9096":
                return List.of("https://github.com/itext/itextpdf/releases/tag/5.5.13.4",
                        "https://kb.itextpdf.com/it5kb/can-itext-2-1-7-itextsharp-4-1-6-or-earlier-be-use");
            case "cn.hutool:hutool-core@5.8.20|CVE-2023-42276":
            case "cn.hutool:hutool-core@5.8.20|CVE-2023-42277":
                return List.of("https://github.com/dromara/hutool/issues/3285",
                        "https://github.com/dromara/hutool/issues/3286");
            default: return List.of();
        }
    }

    private static String key(FindingView finding) {
        if (!"Trivy".equalsIgnoreCase(finding.getScanner()) || !"VULNERABILITY".equals(finding.getCategory())) return "";
        String snippet = finding.getCodeSnippet();
        return (snippet == null ? "" : snippet.split("\\R", 2)[0].trim()) + "|" + finding.getRuleId();
    }
}
