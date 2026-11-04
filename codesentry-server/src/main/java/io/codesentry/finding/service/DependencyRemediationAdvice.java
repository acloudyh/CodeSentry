package io.codesentry.finding.service;

import io.codesentry.finding.model.FindingView;
import java.util.*;
import java.util.regex.*;

/** Offline advice: scanner facts first; curated migrations are dated and cite upstream sources. */
public final class DependencyRemediationAdvice {
    private static final String LEGACY_PREFIX = "升级到修复版本：";
    private static final Set<String> DOM4J_CVES = Set.of("CVE-2020-10683", "CVE-2018-1000632");
    private DependencyRemediationAdvice() {}

    public static String forFinding(FindingView finding) {
        String original = value(finding.getRecommendation());
        if (!"Trivy".equalsIgnoreCase(finding.getScanner()) || !"VULNERABILITY".equals(finding.getCategory()))
            return original.isBlank() ? "请结合代码上下文复核并按安全编码规范整改。" : original;
        // Keep explicit scanner/manual advice; enrich the historical placeholder at render time.
        if (!original.isBlank() && !original.startsWith(LEGACY_PREFIX)
                && !"升级到修复版本。".equals(original)) return original;
        String signature = value(finding.getCodeSnippet()).split("\\R", 2)[0].trim();
        int at = signature.lastIndexOf('@');
        String pkg = at < 0 ? signature : signature.substring(0, at);
        String installed = at < 0 ? "" : signature.substring(at + 1);
        String fixed = original.startsWith(LEGACY_PREFIX) ? original.substring(LEGACY_PREFIX.length()).trim() : "";
        if (fixed.contains("请查阅漏洞公告")) fixed = "";
        return fromScan(pkg, installed, finding.getRuleId(), fixed, "");
    }

    public static String fromScan(String pkg, String installed, String cve, String fixed, String primaryUrl) {
        pkg = value(pkg); installed = value(installed); fixed = value(fixed);
        String current = "当前组件：" + (pkg.isBlank() ? "未识别组件" : pkg)
                + "；当前版本：" + (installed.isBlank() ? "扫描结果未提供" : installed) + "。\n";
        if ("log4j:log4j".equalsIgnoreCase(pkg) && installed.startsWith("1.")) {
            return current + "升级方案：Log4j 1.x 已停止维护，没有可继续使用的官方 1.x 修复版本；1.2.17 也不是本组漏洞的安全终点。\n"
                    + "建议迁移目标：Log4j 2.26.1（2026-11-03 核对的迁移目标，升级前核对 JDK 和框架兼容性）。\n"
                    + "操作：删除 WEB-INF/lib、构建依赖及打包产物中的旧 log4j-1.x JAR，包括 .jar128 等改名副本；"
                    + "引入 org.apache.logging.log4j:log4j-api:2.26.1 和 log4j-core:2.26.1，并迁移日志配置。"
                    + "暂不能修改 org.apache.log4j 调用时，可评估同版本 log4j-1.2-api 桥接包，不能与旧 log4j JAR 共存。\n"
                    + "验证：测试日志输出、滚动归档及自定义 Appender，重新打包并复扫全部相关 CVE。\n"
                    + "依据：https://logging.apache.org/log4j/2.x/migrate-from-log4j1.html ；"
                    + "https://logging.apache.org/security.html";
        }
        if (isAxis(pkg) && installed.startsWith("1.") && "CVE-2023-40743".equals(value(cve))) {
            return current + "官方结论：Axis 1.x 已停止维护，未计划发布本漏洞的 1.x 修复版（2026-11-03 核实）。\n"
                    + "处理：评估迁移 Axis2；暂时无法迁移时，检查 ServiceFactory.getService 的输入来源，限制不可信输入，并评估官方源码补丁。\n"
                    + "验证：SOAP 调用兼容测试后复扫；该补丁不代表本组件其他漏洞均已修复。\n"
                    + "依据：https://lists.apache.org/thread/gs0qgk2mgss7zfhzdd6ftfjvm4kp7v82 ；"
                    + "https://github.com/apache/axis-axis1-java/commit/7e66753427466590d6def0125e448d2791723210";
        }
        if (("dom4j:dom4j".equalsIgnoreCase(pkg) || "org.dom4j:dom4j".equalsIgnoreCase(pkg))
                && DOM4J_CVES.contains(value(cve))
                && (installed.startsWith("1.") || installed.startsWith("2.0.")
                || installed.matches("2\\.1\\.[0-3]"))) {
            return current + "建议升级目标：org.dom4j:dom4j:2.1.4（2026-11-03 核对）。"
                    + "针对 CVE-2020-10683，2.1.x 分支的修复起点为 2.1.3；2.1.1 已处理 CVE-2018-1000632 对应的 XML 注入问题。"
                    + "2.1.4 是覆盖这两条问题的候选目标，不表示不存在其他漏洞。\n"
                    + "操作：将旧 dom4j:dom4j 依赖改为 org.dom4j:dom4j:2.1.4；手工部署时删除 dom4j-1.6.1.jar 等旧包并放入 dom4j-2.1.4.jar，排除重复或传递引入的旧包。\n"
                    + "代码检查：XML 解析使用 SAXReader.createDefault() 等安全配置，禁用外部实体和外部 DTD；不能只换 JAR 而保留不安全解析配置。\n"
                    + "验证：测试 XML 解析、XPath 及依赖兼容性，重新打包后复扫。\n"
                    + "依据：https://github.com/dom4j/dom4j/releases/tag/version-2.1.3 ；"
                    + "https://github.com/advisories/GHSA-6pcc-3rfx-4gpm ；"
                    + "https://github.com/dom4j/dom4j/releases/tag/version-2.1.4";
        }
        String url = value(primaryUrl);
        if (url.isBlank() && value(cve).matches("CVE-[0-9]{4}-[0-9]+")) url = "https://nvd.nist.gov/vuln/detail/" + cve;
        String advice = fixed.isBlank()
                ? "修复版本：扫描结果未给出，需核实官方修复方案。"
                    + "处理方式：移除未使用的依赖；必须使用时，依据该漏洞公告评估禁用受影响功能或迁移到受维护的替代组件，再确认目标版本。"
                : "本漏洞修复版本（扫描时漏洞库记录）：" + fixed + "。"
                    + "多个版本表示不同分支的修复记录，不表示随意选一个就能解决全部漏洞。"
                    + "操作：选择兼容且仍受维护、同时满足本组件所有 CVE 修复要求的版本，修改构建依赖或替换 JAR，排除旧包后重新打包复扫。";
        return current + advice + (url.isBlank() ? "" : "\n漏洞依据：" + url);
    }

    /** Report body: share handling once per component, retaining each CVE's version facts. */
    public static String compact(List<FindingView> members) { return compact(members, null); }

    public static String compact(List<FindingView> members, Integer javaVersion) {
        FindingView first = members.get(0);
        if (!isDependency(first)) return codeAdvice(first);
        String java8 = Java8RemediationAdvice.recommend(members, javaVersion);
        if (java8 != null) return java8;
        String signature = value(first.getCodeSnippet()).split("\\R", 2)[0];
        int at = signature.lastIndexOf('@');
        String pkg = at < 0 ? signature : signature.substring(0, at);
        String installed = at < 0 ? "" : signature.substring(at + 1);
        if ("log4j:log4j".equalsIgnoreCase(pkg) && installed.startsWith("1.")) {
            return "建议目标：迁移至 Log4j 2.26.1（1.x 已停止维护）。\n"
                    + "处理：移除全部旧 1.x 包，引入同版本 log4j-api、log4j-core 并迁移日志配置；旧调用可评估 log4j-1.2-api 桥接。\n"
                    + "验证：日志输出与滚动归档测试后复扫。";
        }
        if (("dom4j:dom4j".equalsIgnoreCase(pkg) || "org.dom4j:dom4j".equalsIgnoreCase(pkg))
                && members.stream().allMatch(f -> DOM4J_CVES.contains(value(f.getRuleId())))
                && (installed.startsWith("1.") || installed.startsWith("2.0.") || installed.matches("2\\.1\\.[0-3]"))) {
            return "建议目标：org.dom4j:dom4j:2.1.4。\n"
                    + "处理：移除旧包并更换依赖坐标；使用安全 XML 解析配置，禁用外部实体及外部 DTD。\n"
                    + "验证：XML 解析与 XPath 测试后复扫。";
        }
        if (isAxis(pkg) && installed.startsWith("1.")
                && members.stream().anyMatch(f -> "CVE-2023-40743".equals(f.getRuleId()))) {
            return "官方结论：Axis 1.x 已停止维护，未计划发布 CVE-2023-40743 的修复版。\n"
                    + "处理：优先移除；必须使用时评估迁移 Axis2（需改造，不能只换 JAR）；过渡期可按官方补丁处理该漏洞。\n"
                    + "验证：SOAP 调用兼容测试后，复扫全部所列漏洞。";
        }
        Map<String, List<String>> fixes = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        Set<String> explicit = new LinkedHashSet<>();
        Pattern pattern = Pattern.compile("本漏洞修复版本（扫描时漏洞库记录）：([^。\\n]+)");
        for (FindingView finding : members) {
            String text = forFinding(finding);
            Matcher match = pattern.matcher(text);
            if (match.find()) fixes.computeIfAbsent(match.group(1), k -> new ArrayList<>()).add(value(finding.getRuleId()));
            else if (text.contains("当前漏洞库未提供") || text.contains("扫描结果未给出")) missing.add(value(finding.getRuleId()));
            else explicit.add(text);
        }
        List<String> lines = new ArrayList<>();
        if (!fixes.isEmpty()) {
            lines.add("修复记录：");
            fixes.forEach((versions, cves) -> lines.add(String.join("、", cves) + "：" + versions));
        }
        if (!missing.isEmpty()) {
            lines.add(fixes.isEmpty() ? "修复版本：扫描结果未给出，需核实官方方案。"
                    : "修复版本待核实：" + String.join("、", missing) + "。");
        }
        lines.addAll(explicit);
        if (!fixes.isEmpty() || !missing.isEmpty()) {
            lines.add(fixes.isEmpty()
                    ? "处理：优先移除未使用依赖；必须使用时，核实官方补丁或迁移方案。"
                    : "处理：选择覆盖全部所列漏洞的受维护版本，同步核对相关组件，排除旧包后替换。");
            lines.add("验证：兼容测试后复扫。");
        }
        return String.join("\n", lines);
    }

    public static List<String> references(FindingView finding) {
        if (!isDependency(finding)) return List.of();
        Set<String> urls = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("https?://[A-Za-z0-9:/?._~%#=&+@-]+").matcher(forFinding(finding));
        while (matcher.find()) urls.add(matcher.group());
        // Also enrich previously archived Axis recommendations without changing original records.
        if (value(finding.getCodeSnippet()).startsWith("axis:axis@1.")
                || value(finding.getCodeSnippet()).startsWith("org.apache.axis:axis@1.")) {
            if ("CVE-2023-40743".equals(finding.getRuleId())) {
                urls.add("https://lists.apache.org/thread/gs0qgk2mgss7zfhzdd6ftfjvm4kp7v82");
                urls.add("https://github.com/apache/axis-axis1-java/commit/7e66753427466590d6def0125e448d2791723210");
            }
        }
        urls.addAll(Java8RemediationAdvice.references(finding));
        return new ArrayList<>(urls);
    }

    private static String codeAdvice(FindingView f) {
        String rule = value(f.getRuleId()).toLowerCase(Locale.ROOT);
        if (rule.contains("missing-integrity")) {
            return "当前：外部 CSS/JS 标签缺少 integrity，浏览器无法校验资源是否被篡改。\n"
                    + "处理：未使用的示例页从发布包移除；保留则固定资源版本，为所列 link/script 添加与实际文件匹配的 SHA-384 integrity，跨域引用配置 crossorigin=anonymous。\n"
                    + "验证：校验值必须按实际文件生成，测试加载和样式脚本功能后复扫。";
        }
        if (("Trivy".equalsIgnoreCase(f.getScanner()) || "Gitleaks".equalsIgnoreCase(f.getScanner()))
                && (rule.contains("private-key") || rule.contains("pkcs12"))) {
            return "当前：仓库文件疑似包含私钥或带私钥的证书容器，需确认是否为有效业务凭据。\n"
                    + "处理：确认有效后更换密钥及相应证书，移出仓库和发布包，改为部署时挂载受限文件；PKCS#12 先核实是否含私钥。\n"
                    + "验证：业务使用新凭据正常，旧凭据已撤销或失效，复扫不再检出。";
        }
        if (("Trivy".equalsIgnoreCase(f.getScanner()) || "Gitleaks".equalsIgnoreCase(f.getScanner()))
                && rule.contains("api-key")) {
            return "当前：所列配置行疑似硬编码访问密钥，需确认真实性及用途。\n"
                    + "处理：有效密钥在对应服务端轮换，配置改从环境变量或密钥文件读取，并清除仓库及发布包中的旧值。\n"
                    + "验证：新密钥调用正常，旧密钥已禁用，重新扫描配置文件。";
        }
        return forFinding(f);
    }

    private static boolean isDependency(FindingView finding) {
        return "Trivy".equalsIgnoreCase(finding.getScanner()) && "VULNERABILITY".equals(finding.getCategory());
    }
    private static boolean isAxis(String pkg) {
        return "axis:axis".equalsIgnoreCase(pkg) || "org.apache.axis:axis".equalsIgnoreCase(pkg);
    }

    private static String value(String value) { return value == null ? "" : value.trim(); }
}
