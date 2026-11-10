package io.codesentry.finding.model;

import java.util.*;

/** A dated lookup for one exact Maven coordinate and CVE, saved with a scan. */
public class OnlineRemediationEvidence {
    public String coordinate;
    public String installedVersion;
    public String cve;
    public String checkedAt;
    public String status;
    public String message;
    public List<String> sources = new ArrayList<>();
    public List<String> fixedVersions = new ArrayList<>();
    // Exact targets re-queried against OSV; never infer safety across release branches.
    public Map<String, Integer> verifiedTargets = new LinkedHashMap<>();

    public String description() {
        String label;
        switch (status == null ? "" : status) {
            case "FOUND": label = "已取得修复记录"; break;
            case "NO_FIXED_VERSION": label = "所查询记录未列修复版本，仍需核实上游方案"; break;
            case "QUERY_FAILED": label = "联网查询失败，沿用本地资料"; break;
            case "LIMIT_REACHED": label = "本次查询达到时限或数量上限，尚未核实"; break;
            case "UNVERIFIED": label = "已取得候选版本，兼容性或修复覆盖尚未核实"; break;
            default: label = "所查询来源未匹配到该组件的修复记录";
        }
        return "联网核实：" + label + "（" + checkedAt + "）"
                + (fixedVersions.isEmpty() ? "。" : "；记录版本：" + String.join("、", fixedVersions) + "。")
                + (verifiedTargets.isEmpty() ? "" : "已核实候选：" + verifiedTargets.entrySet().stream()
                    .map(e -> e.getKey() + "（Java 要求至少 " + e.getValue() + "）")
                    .collect(java.util.stream.Collectors.joining("、")) + "；仍需核对依赖与业务兼容性。")
                + (message == null || message.isEmpty() ? "" : message);
    }
}
