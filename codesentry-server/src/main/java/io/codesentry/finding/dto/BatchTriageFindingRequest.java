package io.codesentry.finding.dto;

import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import javax.validation.constraints.AssertTrue;
import java.time.LocalDateTime;
import java.util.List;

/** Exact, bounded selectors for safe batch triage. This never deletes findings. */
public class BatchTriageFindingRequest {
    @NotBlank
    @Pattern(regexp = "CONFIRMED|FALSE_POSITIVE|ACCEPTED_RISK|FIXED_PENDING")
    private String triageStatus;

    @Size(max = 2000)
    private String comment;

    @NotBlank
    @Size(max = 128)
    private String reviewer;

    private LocalDateTime expiresAt;

    @NotEmpty
    @Size(max = 500)
    @Valid
    private List<Selector> selectors;

    public String getTriageStatus() { return triageStatus; }
    public void setTriageStatus(String triageStatus) { this.triageStatus = triageStatus; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public String getReviewer() { return reviewer; }
    public void setReviewer(String reviewer) { this.reviewer = reviewer; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public List<Selector> getSelectors() { return selectors; }
    public void setSelectors(List<Selector> selectors) { this.selectors = selectors; }

    public static class Selector {
        private Long findingId;

        private String scanner;

        private String ruleId;

        private String filePath;

        @Min(1)
        private Integer startLine;

        @Min(1)
        private Integer endLine;

        public Long getFindingId() { return findingId; }
        public void setFindingId(Long findingId) { this.findingId = findingId; }
        public String getScanner() { return scanner; }
        public void setScanner(String scanner) { this.scanner = scanner; }
        public String getRuleId() { return ruleId; }
        public void setRuleId(String ruleId) { this.ruleId = ruleId; }
        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }
        public Integer getStartLine() { return startLine; }
        public void setStartLine(Integer startLine) { this.startLine = startLine; }
        public Integer getEndLine() { return endLine; }
        public void setEndLine(Integer endLine) { this.endLine = endLine; }

        @AssertTrue(message = "批量复核选择器必须提供问题编号或完整位置")
        public boolean isValidSelector() {
            return findingId != null || (hasText(scanner) && hasText(ruleId) && hasText(filePath)
                    && startLine != null && startLine > 0);
        }

        private boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }

        public String describe() {
            if (findingId != null) return "finding#" + findingId;
            return scanner + "/" + ruleId + " @ " + filePath + ":" + startLine
                    + (endLine == null ? "" : "-" + endLine);
        }
    }
}
