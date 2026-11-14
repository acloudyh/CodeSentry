package io.codesentry.finding.service;

import io.codesentry.finding.model.FindingView;
import java.util.List;
import java.util.Locale;

/** Short, location-aware instructions for the report's recommendation column. */
final class DependencyRemediationPlan {
  private DependencyRemediationPlan() {}

  static String steps(List<FindingView> members, Integer javaVersion, boolean missingFixes) {
    FindingView finding = members.get(0);
    String signature = value(finding.getCodeSnippet()).split("\\R", 2)[0];
    int at = signature.lastIndexOf('@');
    String coordinate = at < 0 ? signature : signature.substring(0, at);
    String installed = at < 0 ? "" : signature.substring(at + 1);
    String[] parts = coordinate.split(":");
    String artifact = parts.length == 2 ? parts[1] : "";
    String path = value(finding.getFilePath());
    String leaf = path.substring(path.lastIndexOf('/') + 1);
    boolean container =
        !artifact.isBlank()
            && leaf.toLowerCase(Locale.ROOT).endsWith(".jar")
            && !leaf.equals(artifact + "-" + installed + ".jar")
            && !leaf.equals(artifact + ".jar");
    String action =
        container
            ? "检查外层包 " + leaf + "；若组件已内嵌，需升级或重建外层包，仅修改业务侧依赖版本无效。"
            : "在 pom.xml 升级该依赖；传递依赖通过 dependencyManagement 统一版本，重新打包并移除旧包。";
    String caution = missingFixes ? "部分漏洞修复版本未明确，需上游确认，不能视为全部修复。" : "";
    if (coordinate.startsWith("cn.hutool:")) caution += "统一所用 hutool-* 模块版本。";
    else if (coordinate.startsWith("io.netty:")) caution += "对齐 netty-* 模块版本。";
    else if (coordinate.equals("commons-collections:commons-collections")) caution += "勿开启危险反序列化。";
    if (javaVersion != null) caution += "保持 Java " + javaVersion + " 兼容。";
    return action + (caution.isBlank() ? "" : "\n注意：" + caution);
  }

  static String validation(FindingView finding) {
    String signature = value(finding.getCodeSnippet());
    String checks =
        signature.startsWith("cn.hutool:")
            ? "JSON 解析及异常输入"
            : signature.startsWith("io.netty:")
                ? "TLS 握手与业务通信"
                : signature.startsWith("com.baomidou:")
                    ? "租户隔离与 SQL 查询"
                    : signature.startsWith("commons-collections:") ? "集合与序列化功能" : "相关业务功能";
    return "回归" + checks + "，上传新包全量复扫，逐项确认所列 CVE。";
  }

  private static String value(String value) {
    return value == null ? "" : value.trim();
  }
}
