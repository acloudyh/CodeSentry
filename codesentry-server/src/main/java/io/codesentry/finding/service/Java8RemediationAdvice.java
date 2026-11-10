package io.codesentry.finding.service;

import io.codesentry.finding.model.FindingView;
import java.util.*;

/** Java 8 remediation candidates, reviewed 2026-11-03; never changes the business repository. */
final class Java8RemediationAdvice {
    private Java8RemediationAdvice() {}
    static String recommend(List<FindingView> members, Integer javaVersion) {
        if (!Integer.valueOf(8).equals(javaVersion)) return null;
        FindingView f = members.get(0);
        String signature = value(f.getCodeSnippet()).split("\\R", 2)[0];
        int at = signature.lastIndexOf('@');
        String pkg = at < 0 ? signature : signature.substring(0, at);
        String version = at < 0 ? "未识别" : signature.substring(at + 1);
        String prefix = "当前：" + pkg + " " + version + "（Java 8）。\n";
        if (pkg.equals("org.apache.ant:ant") && version.equals("1.7.1")
                && known(members, "CVE-2020-11979", "CVE-2020-1945", "CVE-2021-36373")) {
            return prefix + "问题：临时文件可被篡改，恶意 TAR 文件可能耗尽内存。\n"
                    + "建议目标：org.apache.ant:ant:1.10.18。\n"
                    + "处理：业务未使用则移除；仍需使用则替换 ant.jar，核对配套 ant-* 包，设置仅服务账户可写的临时目录。\n"
                    + "验证：构建或业务调用测试后复扫。";
        }
        if (pkg.equals("org.apache.xmlbeans:xmlbeans") && (version.equals("2.3.0") || version.equals("2.6.0"))
                && known(members, "CVE-2021-23926")) {
            return prefix + "问题：XML 外部实体解析可能读取服务器文件或发起外部请求。\n"
                    + "建议目标：org.apache.xmlbeans:xmlbeans:5.3.0，与 POI 5.5.1 的声明依赖一致。\n"
                    + "处理：移除旧 xmlbeans 及不兼容的旧 OOXML schema 包，按 POI 5.5.1 依赖组合更新；自生成 XML 类型需重新编译验证。\n"
                    + "验证：XML、Excel 导入导出及 schema 类型加载测试后复扫。";
        }
        if ((pkg.equals("axis:axis") || pkg.equals("org.apache.axis:axis")) && version.equals("1.4")
                && known(members, "CVE-2023-40743", "CVE-2019-0227", "CVE-2012-5784", "CVE-2014-3596", "CVE-2018-8032")) {
            return prefix + "问题：存在服务查找、HTTPS 主机名校验及页面输出等风险，官方 1.x 已停止维护。\n"
                    + "实施限制：尚未核定覆盖全部所列漏洞的 Java 8 替换版本，不能承诺只换 JAR 完成修复。\n"
                    + "处理：未使用则移除；仍需使用则向供应方获取 Java 8 修复包。CVE-2023-40743 可评估官方源码补丁并限制不可信服务查找输入；该补丁不覆盖其余漏洞。\n"
                    + "验证：逐项核对补丁覆盖范围，测试 SOAP 调用和错误主机名证书拒绝行为后复扫。";
        }
        if (pkg.startsWith("org.apache.poi:") && (version.equals("3.7") || version.equals("3.11") || version.equals("3.15"))
                && known(members, "CVE-2017-12626", "CVE-2019-12415", "CVE-2017-5644", "CVE-2014-9527",
                    "CVE-2014-3574", "CVE-2014-3529", "CVE-2012-0213", "CVE-2025-31672", "CVE-2022-26336")) {
            return prefix + "问题：恶意 Office 文件可能导致服务不可用或读取服务器文件。\n"
                    + "建议目标：统一 POI 组件至 5.5.1；Java 8 不使用要求 Java 17 的 POI 6.x。\n"
                    + "处理：清除 3.7、3.11、3.15 及 .jar37 等旧副本，配套更新 poi、poi-ooxml 等所用模块及其依赖；原 POI 调用需重新编译、适配。\n"
                    + "验证：Excel 导入导出、公式和格式回归后复扫。";
        }
        if (pkg.equals("org.apache.httpcomponents:httpclient") && version.equals("4.5.9")
                && known(members, "CVE-2020-13956")) {
            return prefix + "问题：特殊 URL 可能被解析成非预期请求目标。\n"
                    + "建议目标：org.apache.httpcomponents:httpclient:4.5.14。\n"
                    + "处理：更新同分支客户端及配套依赖；若来自合并包，须重建原包，不能只旁放新 JAR。\n"
                    + "验证：HTTPS、代理、重定向及连接池测试后复扫。";
        }
        if (pkg.equals("commons-httpclient:commons-httpclient") && version.equals("3.1")
                && known(members, "CVE-2012-5783")) {
            return prefix + "问题：HTTPS 主机名校验不充分，存在中间人攻击风险。\n"
                    + "迁移候选：org.apache.httpcomponents:httpclient:4.5.14，与项目现有 4.x 路线统一。\n"
                    + "处理：修改 org.apache.commons.httpclient 旧 API 调用，再移除 commons-httpclient-3.1.jar；不能直接更名换包。\n"
                    + "验证：错误主机名证书必须被拒绝，完成 HTTP 调用回归后复扫。";
        }
        if ((pkg.equals("ch.qos.logback:logback-core") || pkg.equals("ch.qos.logback:logback-classic"))
                && version.equals("1.1.3") && known(members, "CVE-2017-5929", "CVE-2023-6378", "CVE-2021-42550",
                    "CVE-2024-12798", "CVE-2024-12801", "CVE-2025-11226", "CVE-2026-10532", "CVE-2026-1225", "CVE-2026-9828")) {
            return prefix + "问题：旧 Java 8 分支已停止维护；不能以 1.3.16 视为本组全部漏洞已修复，1.5/1.6 又要求 Java 11。\n"
                    + "迁移候选：Java 8 可用的 Log4j 2.26.1 日志实现。\n"
                    + "处理：重建含旧 Logback 的合并包；迁移日志配置，引入同版本 log4j-api、log4j-core；SLF4J 1.7 调用配 log4j-slf4j-impl；直接调用 Logback 的代码需改造。\n"
                    + "验证：移除旧 classic/core，检查日志初始化、输出和滚动归档后复扫。";
        }
        if (pkg.equals("junit:junit") && version.equals("4.12") && known(members, "CVE-2020-15250")) {
            return prefix + "问题：测试临时目录可能向其他本机用户暴露文件。\n"
                    + "建议目标：仅测试使用则从生产包移除，构建范围设为 test；运行时确需依赖则升级至 junit:junit:4.13.2。\n"
                    + "验证：运行测试并检查最终发布包，复扫确认旧 JAR 已清除。";
        }
        return null;
    }
    static List<String> references(FindingView f) {
        String signature = value(f.getCodeSnippet());
        if (signature.startsWith("org.apache.ant:ant@")) return List.of("https://ant.apache.org/security.html", "https://ant.apache.org/bindownload.cgi");
        if (signature.startsWith("org.apache.xmlbeans:xmlbeans@")) return List.of("https://xmlbeans.apache.org/index.html", "https://repo.maven.apache.org/maven2/org/apache/poi/poi-ooxml/5.5.1/poi-ooxml-5.5.1.pom");
        if (signature.startsWith("org.apache.poi:")) return List.of("https://poi.apache.org/changes", "https://poi.apache.org/help/faq");
        if (signature.startsWith("ch.qos.logback:")) return List.of("https://logback.qos.ch/news.html", "https://logback.qos.ch/download.html", "https://logging.apache.org/log4j/2.x/manual/installation.html");
        if (signature.startsWith("org.apache.httpcomponents:httpclient@") || signature.startsWith("commons-httpclient:commons-httpclient@")) return List.of("https://hc.apache.org/httpcomponents-client-4.5.x/current/tutorial/html/");
        if (signature.startsWith("junit:junit@")) return List.of("https://junit.org/junit4/dependency-info.html");
        return List.of();
    }
    private static boolean known(List<FindingView> findings, String... cves) {
        Set<String> allowed = Set.of(cves);
        return findings.stream().allMatch(f -> allowed.contains(f.getRuleId()));
    }
    private static String value(String v) { return v == null ? "" : v.trim(); }
}
