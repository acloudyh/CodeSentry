package io.codesentry.finding.service;

import io.codesentry.finding.model.FindingView;

import java.util.Locale;

/** Shared grouping identity for dependency CVEs in reports and tool review jobs. */
public final class DependencyFindingGrouping {
    private DependencyFindingGrouping() {}

    public static String groupKey(FindingView finding) {
        if (!isDependencyVulnerability(finding)) return null;
        String signature = componentSignature(finding);
        String path = value(finding.getFilePath()).replace('\\', '/').toLowerCase(Locale.ROOT);
        if (signature.isBlank() || path.isBlank()) return null;
        return path + "|" + signature;
    }

    public static String componentSignature(FindingView finding) {
        String snippet = value(finding.getCodeSnippet()).trim();
        int newline = snippet.indexOf('\n');
        return (newline < 0 ? snippet : snippet.substring(0, newline)).toLowerCase(Locale.ROOT);
    }

    public static String componentLabel(FindingView finding) {
        String signature = componentSignature(finding);
        int versionSeparator = signature.indexOf('@');
        String coordinate = versionSeparator < 0 ? signature : signature.substring(0, versionSeparator);
        int artifactSeparator = coordinate.lastIndexOf(':');
        String artifact = artifactSeparator < 0 ? coordinate : coordinate.substring(artifactSeparator + 1);
        if (artifact.isBlank()) return "依赖组件";
        if ("log4j".equals(artifact)) return "Log4j";
        if ("poi".equals(artifact)) return "POI";
        if ("axis".equals(artifact)) return "Axis";
        return artifact;
    }

    private static boolean isDependencyVulnerability(FindingView finding) {
        return "Trivy".equalsIgnoreCase(value(finding.getScanner()))
                && "VULNERABILITY".equalsIgnoreCase(value(finding.getCategory()));
    }

    private static String value(String value) { return value == null ? "" : value; }
}
