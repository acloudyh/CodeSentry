package io.codesentry.finding.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.finding.model.FindingView;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/** Offline group resolution. Project Java is an input constraint, never an upgrade action. */
final class UnifiedDependencyRemediation {
  private static final JsonNode RULES = read("java-compatibility.json").path("rules");
  private static final JsonNode SUPPLEMENTS = read("supplemental-fixes.json").path("findings");
  private static final JsonNode SPRING = read("spring-official-fixes.json").path("findings");
  private static final Pattern SCAN_FIX = Pattern.compile("本漏洞修复版本（扫描时漏洞库记录）：([^。\\n]+)");

  private UnifiedDependencyRemediation() {}

  static String recommend(List<FindingView> members, Integer javaVersion) {
    FindingView first = members.get(0);
    String pkg = coordinate(first), installed = installed(first);
    String constraint = javaVersion == null ? "项目 Java 版本：未确认，暂不指定升级版本。\n" : "";
    if (javaVersion == null) return constraint + unresolved(members, null);
    if (members.stream().anyMatch(f -> !pkg.equals(coordinate(f))))
      return constraint + unresolved(members, javaVersion);
    if (pkg.startsWith("ch.qos.logback:") && javaVersion >= 8) {
      return constraint
          + "统一迁移目标：Log4j 2.26.1（替换 Logback 日志实现）。\n"
          + "处理：移除 logback-classic/core，迁移日志配置；SLF4J 1.7 配 log4j-slf4j-impl，SLF4J 2 配 log4j-slf4j2-impl，统一 log4j-* 版本并排除反向桥接冲突。\n"
          + "覆盖方式：彻底移除本组受影响组件；替换与配置适配完成前不视为修复。\n验证：回归日志初始化、输出和滚动归档，重新打包全量复扫。";
    }
    if (members.size() == 1
        && !pkg.startsWith("org.springframework:")
        && !pkg.startsWith("org.bouncycastle:")) {
      String verified = VerifiedDependencyRemediation.advice(first, javaVersion);
      if (verified != null) return constraint + verified;
    }
    List<List<Fix>> facts =
        members.stream().map(UnifiedDependencyRemediation::fixes).collect(Collectors.toList());
    Set<String> candidates = new LinkedHashSet<>();
    facts.forEach(xs -> xs.forEach(f -> candidates.add(normalize(pkg, f.version))));
    candidates.add(installed);
    for (JsonNode rule : RULES)
      if (targetCoordinate(pkg).matches(rule.path("package").asText()))
        rule.path("preferred").forEach(v -> candidates.add(v.asText()));
    Candidate best = null;
    for (String target : candidates) {
      JsonNode rule = rule(pkg, target);
      if (rule == null) rule = onlineRule(members, target);
      if (rule == null
          || rule.path("java").asInt() > javaVersion
          || (rule.has("maxJava") && javaVersion > rule.path("maxJava").asInt())
          || !stable(target)
          || !stable(installed)
          || compare(target, installed) < 0) continue;
      List<Integer> covered = new ArrayList<>();
      boolean enterprise = false;
      for (int i = 0; i < facts.size(); i++) {
        Fix hit = match(facts.get(i), pkg, target, rule.path("branchParts").asInt());
        if (hit != null) {
          covered.add(i);
          enterprise |= hit.enterprise;
        }
      }
      Candidate current =
          new Candidate(
              target,
              covered,
              enterprise,
              branch(target, rule.path("branchParts").asInt())
                  .equals(branch(installed, rule.path("branchParts").asInt())),
              preferred(rule, target));
      if (best == null || better(current, best)) best = current;
    }
    if (best == null || best.covered.isEmpty())
      return constraint + unresolved(members, javaVersion);
    List<FindingView> pending = new ArrayList<>();
    for (int i = 0; i < members.size(); i++)
      if (!best.covered.contains(i)) pending.add(members.get(i));
    boolean httpInvoker =
        pkg.equals("org.springframework:spring-web")
            && pending.size() == 1
            && "CVE-2016-1000027".equals(pending.get(0).getRuleId());
    String access = best.enterprise ? "（企业支持版本，需取得对应补丁包）" : "";
    StringBuilder text = new StringBuilder(constraint);
    if (pending.isEmpty() || httpInvoker) {
      text.append(rule(pkg, best.version) == null ? "统一候选版本：" : "统一推荐版本：")
          .append(targetCoordinate(pkg))
          .append(":")
          .append(best.version)
          .append(access)
          .append("。\n");
      text.append("覆盖情况：")
          .append(best.covered.size())
          .append(" / ")
          .append(members.size())
          .append(" 条有对应版本修复记录");
      text.append(httpInvoker ? "，另 1 条须移除风险入口。\n" : "（限本组所列漏洞，部署后仍须验证）。\n");
    } else {
      text.append("统一推荐版本：尚未核定覆盖整组的版本。\n");
      text.append("部分修复目标：")
          .append(targetCoordinate(pkg))
          .append(":")
          .append(best.version)
          .append(access)
          .append("，版本记录覆盖 ")
          .append(best.covered.size())
          .append(" / ")
          .append(members.size())
          .append(" 条。\n");
      text.append("尚待解决：").append(ids(pending)).append("；不能将部分修复视为整组完成。\n");
    }
    if (rule(pkg, best.version) == null)
      text.append("Java 兼容性待确认：候选仅通过基础类文件字节码要求检查，仍须核对官方运行环境要求、传递依赖及业务兼容性。\n");
    text.append("处理：").append(DependencyRemediationPlan.steps(members, javaVersion, false));
    if (!targetCoordinate(pkg).equals(pkg))
      text.append(
          "Maven 坐标改为 " + targetCoordinate(pkg) + "，移除旧 jdk15on 包并统一 bcprov、bcpkix、bcutil 版本。");
    if (pkg.startsWith("org.springframework:"))
      text.append("统一 spring-* 模块版本；通过框架 BOM 核对配套依赖，企业补丁须覆盖本组全部漏洞。");
    if (pkg.startsWith("org.springframework.boot:"))
      text.append("通过 Spring Boot BOM 对齐全部 Boot 模块，并核对 Spring、Security 和嵌入式容器兼容性。");
    if (pkg.startsWith("org.apache.tomcat")) text.append("统一 tomcat-embed-* 模块版本。");
    if (!branch(best.version, 1).equals(branch(installed, 1)))
      text.append("跨主版本升级须适配 API 并重新编译，不能只替换 JAR。");
    if (pkg.startsWith("com.fasterxml.jackson"))
      text.append("通过 Jackson BOM 对齐 core、databind、annotations 及所用扩展模块。");
    if (httpInvoker)
      text.append("另须移除 HttpInvokerServiceExporter 及对应端点，替换 Java 原生反序列化通信；仅升级 5.3.x 不能消除此项。");
    if (pkg.startsWith("cn.hutool:")
        && pending.stream()
            .anyMatch(
                f ->
                    Set.of("CVE-2023-42276", "CVE-2023-42277")
                        .contains(String.valueOf(f.getRuleId()))))
      text.append("对 JSONArray 索引和 JSONObject.putByPath 路径设置上限，拒绝超大下标及不可信路径；此为临时防护，对应官方修复版本尚未核定。");
    if (!pending.isEmpty() && !httpInvoker)
      text.append("对尚待解决项获取适用于项目 Java 版本的官方回移补丁；未使用则删除组件或受影响功能。临时限制输入或隔离入口仅作缓解，保留未解决项。");
    text.append("\n验证：").append(DependencyRemediationPlan.validation(first));
    if (compare(best.version, installed) == 0) text.append("当前版本已达到所列修复记录，需复核漏洞匹配和实际加载的旧包，不能建议降级。");
    return text.toString();
  }

  static List<String> versionRecords(List<FindingView> members) {
    List<String> records = new ArrayList<>();
    Map<String, Set<String>> onlineRecords = new LinkedHashMap<>();
    for (FindingView finding : members)
      if (finding.getOnlineRemediation() != null)
        onlineRecords
            .computeIfAbsent(
                finding.getOnlineRemediation().description(), k -> new LinkedHashSet<>())
            .add(finding.getRuleId());
    onlineRecords.forEach(
        (description, cves) -> records.add(String.join("、", cves) + "：" + description));
    for (FindingView finding : members) {
      List<Fix> facts = fixes(finding);
      String original = scanVersions(finding);
      String scan = original.isEmpty() ? "未记录明确修复版本" : original;
      records.add(finding.getRuleId() + "：" + scan + "（扫描时漏洞库记录）");
      List<String> upstream =
          facts.stream()
              .filter(f -> f.upstream && !f.exact)
              .map(f -> f.version + (f.enterprise ? "（企业支持）" : ""))
              .distinct()
              .collect(Collectors.toList());
      if (!upstream.isEmpty())
        records.add("官方补充 " + finding.getRuleId() + "：" + String.join("、", upstream));
    }
    return records;
  }

  static List<String> references(FindingView finding) {
    Set<String> sources = new LinkedHashSet<>();
    String pkg = coordinate(finding);
    for (JsonNode rule : RULES)
      if (pkg.matches(rule.path("package").asText())) sources.add(rule.path("source").asText());
    if (pkg.startsWith("org.springframework:") && SPRING.has(finding.getRuleId()))
      sources.add(SPRING.path(finding.getRuleId()).path("url").asText());
    if ("CVE-2016-1000027".equals(finding.getRuleId()))
      sources.add("https://github.com/spring-projects/spring-framework/issues/24434");
    for (JsonNode item : SUPPLEMENTS)
      if (pkg.equals(item.path("package").asText())
          && String.valueOf(finding.getRuleId()).equals(item.path("cve").asText()))
        sources.add(item.path("source").asText());
    if (pkg.equals("com.google.guava:guava"))
      sources.add("https://github.com/google/guava/releases/tag/v32.0.0");
    if (pkg.startsWith("ch.qos.logback:"))
      sources.add("https://logging.apache.org/log4j/2.x/manual/installation.html");
    if (finding.getOnlineRemediation() != null)
      sources.addAll(finding.getOnlineRemediation().sources);
    return new ArrayList<>(sources);
  }

  private static List<Fix> fixes(FindingView finding) {
    List<Fix> facts = new ArrayList<>();
    for (String v : scanVersions(finding).split("[,，]"))
      if (stable(v.trim())) facts.add(new Fix(v.trim(), false, false));
    if (coordinate(finding).startsWith("org.springframework:"))
      for (JsonNode fix : SPRING.path(String.valueOf(finding.getRuleId())).path("versions"))
        if (stable(fix.path("version").asText()))
          facts.add(
              new Fix(fix.path("version").asText(), fix.path("enterprise").asBoolean(), true));
    for (JsonNode item : SUPPLEMENTS)
      if (coordinate(finding).equals(item.path("package").asText())
          && String.valueOf(finding.getRuleId()).equals(item.path("cve").asText()))
        for (JsonNode v : item.path("versions"))
          facts.add(new Fix(v.asText(), false, true, item.path("laterBranches").asBoolean()));
    if (coordinate(finding).equals("com.google.guava:guava")
        && Set.of("CVE-2020-8908", "CVE-2023-2976").contains(String.valueOf(finding.getRuleId())))
      facts.add(new Fix("32.0.0-jre", false, true));
    if (finding.getOnlineRemediation() != null) {
      for (String target : finding.getOnlineRemediation().verifiedTargets.keySet()) {
        Fix fix = new Fix(target, false, true);
        fix.exact = true;
        facts.add(fix);
      }
    }
    return facts;
  }

  private static String scanVersions(FindingView finding) {
    String original = finding.getRecommendation() == null ? "" : finding.getRecommendation();
    Matcher scan = SCAN_FIX.matcher(original);
    if (scan.find()) return scan.group(1);
    if (original.startsWith("升级到修复版本：") && !original.contains("请查阅漏洞公告"))
      return original.substring("升级到修复版本：".length()).trim();
    return "";
  }

  private static Fix match(List<Fix> facts, String pkg, String target, int parts) {
    Fix result = null;
    for (Fix fix : facts) {
      String version = normalize(pkg, fix.version);
      if (pkg.equals("com.google.guava:guava")
          && version.endsWith("-android")
          && target.endsWith("-jre")) continue;
      // Parallel maintenance lines cannot borrow a lower line's backport version.
      boolean inherited = fix.laterBranches && branch(target, 1).equals(branch(version, 1));
      if (fix.exact && !target.equals(version)) continue;
      if ((!branch(target, parts).equals(branch(version, parts)) && !inherited)
          || compare(target, version) < 0) continue;
      if (result == null || (result.enterprise && !fix.enterprise)) result = fix;
    }
    return result;
  }

  private static boolean better(Candidate current, Candidate other) {
    if (current.covered.size() != other.covered.size())
      return current.covered.size() > other.covered.size();
    if (current.preferred != other.preferred) return current.preferred;
    if (current.sameBranch != other.sameBranch) return current.sameBranch;
    if (current.enterprise != other.enterprise) return !current.enterprise;
    return current.sameBranch
        ? compare(current.version, other.version) > 0
        : compare(current.version, other.version) < 0;
  }

  private static String unresolved(List<FindingView> members, Integer javaVersion) {
    return "统一推荐版本：尚未核定。\n尚待解决："
        + ids(members)
        + "。\n处理：未使用的依赖从发布包移除；必须保留时，按附录逐项核对官方补丁或替代组件"
        + (javaVersion == null ? "的 JDK 要求" : "，补丁及替代实现须兼容 Java " + javaVersion)
        + "。限制不可信输入、隔离入口属于临时缓解，不能标为已修复。\n验证：回归相关业务并全量复扫，逐项保留尚未解决的漏洞。";
  }

  private static String targetCoordinate(String pkg) {
    return pkg.startsWith("org.bouncycastle:") && pkg.endsWith("-jdk15on")
        ? pkg.replace("-jdk15on", "-jdk18on")
        : pkg;
  }

  static int compareVersions(String a, String b) {
    return stable(a) && stable(b) ? compare(a, b) : -1;
  }

  static boolean sameReleaseLine(String a, String b) {
    return branch(a, 2).equals(branch(b, 2));
  }

  static Integer minimumJava(String pkg, String version) {
    JsonNode rule = rule(pkg, version);
    return rule == null ? null : rule.path("java").asInt();
  }

  static String javaSource(String pkg, String version) {
    JsonNode rule = rule(pkg, version);
    return rule == null
        ? RemediationHttpClient.artifactUrl(pkg, version)
        : rule.path("source").asText();
  }

  static List<String> localCandidates(List<FindingView> members) {
    Set<String> versions = new LinkedHashSet<>();
    for (FindingView f : members) fixes(f).forEach(fix -> versions.add(fix.version));
    String pkg = coordinate(members.get(0));
    for (JsonNode rule : RULES)
      if (pkg.matches(rule.path("package").asText()))
        rule.path("preferred").forEach(v -> versions.add(v.asText()));
    return new ArrayList<>(versions);
  }

  private static JsonNode onlineRule(List<FindingView> members, String target) {
    Integer java = null;
    for (FindingView f : members)
      if (f.getOnlineRemediation() != null) {
        Integer value = f.getOnlineRemediation().verifiedTargets.get(target);
        if (value != null) java = java == null ? value : Math.max(java, value);
      }
    if (java == null) return null;
    return new ObjectMapper().createObjectNode().put("java", java).put("branchParts", 2);
  }

  private static boolean preferred(JsonNode rule, String target) {
    for (JsonNode v : rule.path("preferred")) if (target.equals(v.asText())) return true;
    return false;
  }

  private static JsonNode rule(String pkg, String version) {
    for (JsonNode rule : RULES)
      if (targetCoordinate(pkg).matches(rule.path("package").asText())
          && version.matches(rule.path("versions").asText())) return rule;
    return null;
  }

  private static String normalize(String pkg, String version) {
    if (pkg.equals("com.google.guava:guava")
        && !version.endsWith("-android")
        && !version.endsWith("-jre")) return version + "-jre";
    return version;
  }

  private static boolean stable(String v) {
    return v.matches("[0-9]+(?:\\.[0-9]+)*(?:(?:\\.Final|\\.RELEASE)|-(?:jre|android))?");
  }

  private static String numeric(String v) {
    return v.replaceFirst("(?:\\.Final|\\.RELEASE|-jre|-android)$", "");
  }

  private static String branch(String v, int parts) {
    if (parts == 0) return "linear";
    return Arrays.stream(numeric(v).split("\\.")).limit(parts).collect(Collectors.joining("."));
  }

  private static int compare(String a, String b) {
    String[] left = numeric(a).split("\\."), right = numeric(b).split("\\.");
    for (int i = 0; i < Math.max(left.length, right.length); i++) {
      int d =
          new BigInteger(i < left.length ? left[i] : "0")
              .compareTo(new BigInteger(i < right.length ? right[i] : "0"));
      if (d != 0) return d;
    }
    return 0;
  }

  private static String signature(FindingView f) {
    return f.getCodeSnippet() == null ? "" : f.getCodeSnippet().split("\\R", 2)[0].trim();
  }

  private static String coordinate(FindingView f) {
    String s = signature(f);
    int at = s.lastIndexOf('@');
    return at < 0 ? s : s.substring(0, at);
  }

  private static String installed(FindingView f) {
    String s = signature(f);
    int at = s.lastIndexOf('@');
    return at < 0 ? "" : s.substring(at + 1);
  }

  private static String ids(List<FindingView> fs) {
    return fs.stream()
        .map(FindingView::getRuleId)
        .filter(Objects::nonNull)
        .distinct()
        .collect(Collectors.joining("、"));
  }

  private static JsonNode read(String name) {
    try (InputStream in =
        UnifiedDependencyRemediation.class.getResourceAsStream("/remediation/" + name)) {
      if (in == null) throw new IllegalStateException("整改规则不存在：" + name);
      return new ObjectMapper().readTree(in);
    } catch (Exception e) {
      throw new IllegalStateException("整改规则读取失败：" + name, e);
    }
  }

  private static final class Fix {
    final String version;
    final boolean enterprise, upstream, laterBranches;
    boolean exact;

    Fix(String version, boolean enterprise, boolean upstream) {
      this(version, enterprise, upstream, false);
    }

    Fix(String version, boolean enterprise, boolean upstream, boolean laterBranches) {
      this.version = version;
      this.enterprise = enterprise;
      this.upstream = upstream;
      this.laterBranches = laterBranches;
    }
  }

  private static final class Candidate {
    final String version;
    final List<Integer> covered;
    final boolean enterprise, sameBranch, preferred;

    Candidate(
        String version,
        List<Integer> covered,
        boolean enterprise,
        boolean sameBranch,
        boolean preferred) {
      this.version = version;
      this.covered = covered;
      this.enterprise = enterprise;
      this.sameBranch = sameBranch;
      this.preferred = preferred;
    }
  }
}
