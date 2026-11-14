package io.codesentry.finding.service;

import io.codesentry.ai.dto.AiReviewSummary;
import io.codesentry.ai.model.AiReviewRecord;
import io.codesentry.ai.service.AiReviewService;
import io.codesentry.finding.model.FindingView;
import java.math.BigDecimal;
import java.util.*;

public final class ReviewedFindingPolicy {
  private static final BigDecimal MIN_CONFIDENCE = new BigDecimal("0.50");
  private static final Set<String> VERDICTS =
      Set.of("CONFIRMED", "CONDITIONAL", "INSUFFICIENT_EVIDENCE");

  private ReviewedFindingPolicy() {}

  public static List<FindingView> select(
      List<FindingView> raw, Map<Long, AiReviewSummary> reviews) {
    List<FindingView> eligible = new ArrayList<>();
    for (FindingView finding : raw) {
      if (isEligible(finding, reviews.get(finding.getId()))) eligible.add(finding);
    }
    return deduplicateFindings(eligible);
  }

  public static boolean isEligible(FindingView finding, AiReviewSummary summary) {
    // 新安全报告不混入编码规范；旧归档仍按归档清单读取，不改写历史结果。
    if ("QUALITY".equals(FindingClassification.type(finding))) return false;
    // Human confirmation takes precedence over AI; export never updates source records.
    if ("FALSE_POSITIVE".equals(finding.getTriageStatus())) return false;
    if ("CONFIRMED".equals(finding.getTriageStatus())) return true;
    if (summary == null
        || !Set.of("COMPLETED", "NEEDS_HUMAN").contains(value(summary.getStatus(), ""))
        || (!VERDICTS.contains(value(summary.getConsolidatedVerdict(), ""))
            && !"NEEDS_HUMAN".equals(summary.getConsolidatedVerdict()))) return false;
    AiReviewRecord primary = summary.getPrimaryReview(), verify = summary.getVerificationReview();
    return validRound(primary, "PRIMARY")
        && validRound(verify, "VERIFY")
        && primary.getReviewJobId() != null
        && Objects.equals(primary.getReviewJobId(), verify.getReviewJobId())
        && Objects.equals(primary.getFindingId(), finding.getId())
        && Objects.equals(verify.getFindingId(), finding.getId())
        && Objects.equals(primary.getScanRunId(), finding.getScanRunId())
        && Objects.equals(verify.getScanRunId(), finding.getScanRunId())
        && primary.getInputHash() != null
        && !primary.getInputHash().isBlank()
        && primary.getInputHash().equals(verify.getInputHash());
  }

  private static boolean validRound(AiReviewRecord record, String round) {
    return record != null
        && round.equals(record.getReviewRound())
        && "COMPLETED".equals(record.getStatus())
        && VERDICTS.contains(value(record.getVerdict(), ""))
        && AiReviewService.PROMPT_VERSION.equals(record.getPromptVersion())
        && record.getConfidence() != null
        && record.getConfidence().compareTo(MIN_CONFIDENCE) > 0
        && record.getConfidence().compareTo(BigDecimal.ONE) <= 0;
  }

  public static String exclusionReason(FindingView finding, AiReviewSummary summary) {
    if (isEligible(finding, summary)) return null;
    if ("QUALITY".equals(FindingClassification.type(finding))) return "编码规范问题，保留在规范整改清单，不计入安全风险";
    if ("FALSE_POSITIVE".equals(finding.getTriageStatus())) return "人工判定误报";
    if (summary == null) return "尚无工具审核结果，且未人工确认风险";
    if (!Set.of("COMPLETED", "NEEDS_HUMAN").contains(value(summary.getStatus(), "")))
      return "工具审核未完成或失败（" + summary.getStatus() + "），需完成审核或人工确认";
    AiReviewRecord primary = summary.getPrimaryReview(), verify = summary.getVerificationReview();
    if (primary == null || verify == null) return "缺少完整的工具初审或复审记录";
    if ("FALSE_POSITIVE".equals(primary.getVerdict())
        || "FALSE_POSITIVE".equals(verify.getVerdict())
        || "FALSE_POSITIVE".equals(summary.getConsolidatedVerdict())) return "工具判为疑似误报，需人工确认";
    if (!AiReviewService.PROMPT_VERSION.equals(primary.getPromptVersion())
        || !AiReviewService.PROMPT_VERSION.equals(verify.getPromptVersion()))
      return "审核协议版本已过期，需重新审核";
    if (primary.getConfidence() == null
        || verify.getConfidence() == null
        || primary.getConfidence().compareTo(MIN_CONFIDENCE) <= 0
        || verify.getConfidence().compareTo(MIN_CONFIDENCE) <= 0)
      return "初审或复审可信度未通过门槛（初审、复审均必须大于 50%）";
    return "审核状态、结论或证据一致性校验未通过，需重新审核或人工确认";
  }

  private static List<FindingView> deduplicateFindings(List<FindingView> rawFindings) {
    Map<String, FindingView> unique = new LinkedHashMap<>();
    for (FindingView finding : rawFindings) {
      String key = dedupKey(finding);
      FindingView existing = unique.get(key);
      if (existing == null && isCookieRule(finding.getRuleId())) {
        existing = findNearbyCookieFinding(unique.values(), finding);
      }
      if (existing == null) {
        unique.put(key, finding);
      }
      // Duplicate scanner hits are intentionally discarded. The raw scanner output
      // remains available for diagnostics, but duplicates never enter the report view.
    }
    return new ArrayList<>(unique.values());
  }

  private static FindingView findNearbyCookieFinding(
      Iterable<FindingView> findings, FindingView candidate) {
    String scanner = value(candidate.getScanner(), "unknown");
    String path =
        value(candidate.getFilePath(), "unknown").replace('\\', '/').replaceAll("^\\./", "");
    Integer line = candidate.getStartLine();
    if (line == null) return null;
    for (FindingView existing : findings) {
      if (!isCookieRule(existing.getRuleId())
          || !scanner.equalsIgnoreCase(value(existing.getScanner(), "unknown"))
          || !path.equals(
              value(existing.getFilePath(), "unknown").replace('\\', '/').replaceAll("^\\./", ""))
          || existing.getStartLine() == null
          || Math.abs(existing.getStartLine() - line) > 4) {
        continue;
      }
      return existing;
    }
    return null;
  }

  private static boolean isCookieRule(String ruleId) {
    String rule = value(ruleId, "unknown").toLowerCase(Locale.ROOT);
    return rule.contains("cookie-missing-httponly")
        || rule.contains("cookie-missing-secure")
        || rule.contains("cookie-issecure-false");
  }

  private static String dedupKey(FindingView finding) {
    String scanner = value(finding.getScanner(), "unknown").toLowerCase(Locale.ROOT);
    String path =
        value(finding.getFilePath(), "unknown").replace('\\', '/').replaceAll("^\\./", "");
    String line =
        String.valueOf(finding.getStartLine()) + "-" + String.valueOf(finding.getEndLine());
    return scanner + "|" + path + "|" + line + "|" + ruleFamily(finding.getRuleId());
  }

  private static String ruleFamily(String ruleId) {
    String rule = value(ruleId, "unknown").toLowerCase(Locale.ROOT);
    if (rule.contains("cookie-missing-httponly")
        || rule.contains("cookie-missing-secure")
        || rule.contains("cookie-issecure-false")) return "cookie-security-attributes";
    if (rule.contains("use-of-md5") || rule.contains("weak-message-digest"))
      return "weak-message-digest";
    return rule;
  }

  private static String value(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
