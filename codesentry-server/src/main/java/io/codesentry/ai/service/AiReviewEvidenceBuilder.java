package io.codesentry.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.ai.dto.AiReviewEvidence;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.finding.model.FindingView;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

@Component
public class AiReviewEvidenceBuilder {
    private static final String REDACTED = "<REDACTED>";
    private static final Pattern PEM = Pattern.compile("-----BEGIN[\\s\\S]*?-----END [^-]+-----", Pattern.CASE_INSENSITIVE);
    private static final Pattern BEARER = Pattern.compile("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s\\\"']+");
    private static final Pattern ASSIGNMENT = Pattern.compile("(?i)(password|passwd|pwd|secret|api[_-]?key|access[_-]?token|client[_-]?secret)(\\s*[:=]\\s*)[^\\s,;\\}\"]+");
    private static final Pattern URL_SECRET = Pattern.compile("(?i)([?&](?:token|access_token|api_key|key|secret|password)=)[^&#\\s\\\"']+");

    private final CodeSentryProperties properties;
    private final ObjectMapper objectMapper;

    public AiReviewEvidenceBuilder(CodeSentryProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public AiReviewEvidence build(FindingView finding) {
        AiReviewEvidence evidence = new AiReviewEvidence();
        evidence.setFindingId(finding.getId());
        evidence.setScanRunId(finding.getScanRunId());
        evidence.setScanner(safe(finding.getScanner()));
        evidence.setRuleId(safe(finding.getRuleId()));
        evidence.setCategory(safe(finding.getCategory()));
        evidence.setSeverity(safe(finding.getSeverity()));
        evidence.setTitle(sanitize(finding.getTitle()));
        evidence.setDescription(limit(sanitize(finding.getDescription()), 2000));
        evidence.setRecommendation(limit(sanitize(finding.getRecommendation()), 2000));
        evidence.setCwe(safe(finding.getCwe()));
        evidence.setFilePath(normalizePath(finding.getFilePath()));
        evidence.setStartLine(finding.getStartLine());
        evidence.setEndLine(finding.getEndLine());
        evidence.setCodeSnippet(limit(sanitize(finding.getCodeSnippet()),
                Math.max(1000, properties.getAi().getMaxContextCharacters())));
        return evidence;
    }

    public String serialize(AiReviewEvidence evidence) {
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成 AI 源码审核包", exception);
        }
    }

    public String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算 AI 审核包摘要", exception);
        }
    }

    String sanitize(String value) {
        if (value == null) return null;
        String result = PEM.matcher(value).replaceAll(REDACTED);
        result = BEARER.matcher(result).replaceAll("$1" + REDACTED);
        result = ASSIGNMENT.matcher(result).replaceAll("$1$2" + REDACTED);
        return URL_SECRET.matcher(result).replaceAll("$1" + REDACTED);
    }

    String normalizePath(String value) {
        if (value == null) return "unknown";
        String path = value.replace('\\', '/').replaceFirst("^\\./", "");
        int snapshot = path.indexOf("/current/");
        if (snapshot >= 0) return path.substring(snapshot + "/current/".length());
        int baseline = path.indexOf("/baseline/");
        if (baseline >= 0) return path.substring(baseline + "/baseline/".length());
        if (path.startsWith("/")) return "<PROJECT>/" + path.substring(path.lastIndexOf('/') + 1);
        return path;
    }

    private String safe(String value) { return value == null ? "" : value; }
    private String limit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max) + "\n<TRUNCATED>";
    }
}
