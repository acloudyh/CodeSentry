package io.codesentry.report.dto;

import io.codesentry.finding.model.FindingView;

public class ReportExclusion {
    private final Long findingId;
    private final String ruleId;
    private final String title;
    private final String filePath;
    private final String reason;

    public ReportExclusion(FindingView finding, String reason) {
        this.findingId = finding.getId();
        this.ruleId = finding.getRuleId();
        this.title = finding.getTitle();
        this.filePath = finding.getFilePath();
        this.reason = reason;
    }
    public Long getFindingId() { return findingId; }
    public String getRuleId() { return ruleId; }
    public String getTitle() { return title; }
    public String getFilePath() { return filePath; }
    public String getReason() { return reason; }
}
