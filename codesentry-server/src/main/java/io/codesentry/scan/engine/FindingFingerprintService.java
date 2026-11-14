package io.codesentry.scan.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class FindingFingerprintService {

  public String calculate(NormalizedFinding finding) {
    String material =
        normalize(finding.getScanner())
            + "|"
            + normalize(finding.getRuleId())
            + "|"
            + normalizePath(finding.getFilePath())
            + "|"
            + normalizeCode(
                finding.getSourceFingerprint(),
                normalizeCode(finding.getCodeSnippet(), finding.getTitle()));
    return digest(material);
  }

  public String semanticFamily(String ruleId) {
    String rule = normalize(ruleId);
    if (rule.contains("cookie-missing-httponly")
        || rule.contains("cookie-missing-secure")
        || rule.contains("cookie-issecure-false")) {
      return "cookie-security-attributes";
    }
    if (rule.contains("use-of-md5") || rule.contains("weak-message-digest")) {
      return "weak-message-digest";
    }
    return null;
  }

  private String normalizePath(String value) {
    return normalize(value).replace('\\', '/').replaceAll("^\\./", "");
  }

  private String normalizeCode(String code, String fallback) {
    String value = code == null || code.trim().isEmpty() ? fallback : code;
    return normalize(value).replaceAll("\\s+", " ");
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private String digest(String material) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
      StringBuilder value = new StringBuilder(hash.length * 2);
      for (byte item : hash) {
        value.append(String.format("%02x", item));
      }
      return value.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
    }
  }
}
