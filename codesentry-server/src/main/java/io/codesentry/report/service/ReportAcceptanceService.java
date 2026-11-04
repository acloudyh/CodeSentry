package io.codesentry.report.service;

import io.codesentry.common.error.BusinessException;
import io.codesentry.finding.model.FindingView;
import io.codesentry.finding.service.FindingPersistenceService;
import io.codesentry.scan.engine.FindingFingerprintService;
import io.codesentry.scan.mapper.ScanRunMapper;
import io.codesentry.scan.model.*;
import io.codesentry.scan.service.ScanToolRunService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReportAcceptanceService {
    private final RemediationReportService reports;
    private final ScanRunMapper runs;
    private final FindingPersistenceService findings;
    private final ScanToolRunService tools;
    private final FindingFingerprintService fingerprints;

    public ReportAcceptanceService(RemediationReportService reports, ScanRunMapper runs,
            FindingPersistenceService findings, ScanToolRunService tools, FindingFingerprintService fingerprints) {
        this.reports = reports; this.runs = runs; this.findings = findings; this.tools = tools; this.fingerprints = fingerprints;
    }

    public Map<String, Object> detail(Long sourceId, Long verificationId) {
        ScanRun source = runs.findById(sourceId);
        if (source == null) throw new BusinessException(HttpStatus.NOT_FOUND, "原报告不存在");
        if (source.getStatus() != ScanRunStatus.COMPLETED && source.getStatus() != ScanRunStatus.PARTIAL)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "扫描结束后才能归档报告并验收");
        List<FindingView> archived = reports.archiveFindings(sourceId);
        List<ScanRun> history = runs.findAcceptanceRuns(sourceId);
        ScanRun target = verificationId == null ? (history.isEmpty() ? null : history.get(0)) : runs.findById(verificationId);
        if (verificationId != null && (target == null || !sourceId.equals(target.getSourceReportRunId())))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "验证批次不属于当前报告");
        List<FindingView> current = target == null ? List.of() : findings.findByRunId(target.getId()).stream()
                .filter(f -> !"FIXED".equals(f.getResultStatus())).collect(Collectors.toList());
        Set<String> covered = target == null ? Set.of() : tools.listByScanRun(target.getId()).stream()
                .filter(t -> "current".equals(t.getPhase()) && "COMPLETED".equals(t.getStatus()))
                .map(t -> normalize(t.getToolName())).collect(Collectors.toSet());
        Map<Long, FindingView> live = new HashMap<>();
        findings.findByRunId(sourceId).forEach(f -> live.put(f.getId(), f));
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (FindingView original : archived) {
            FindingView triage = live.getOrDefault(original.getId(), original);
            String state = status(original, triage, source, target, current, covered);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("finding", original); row.put("status", state);
            row.put("comment", triage.getTriageComment()); row.put("reviewer", triage.getReviewer());
            row.put("triageStatus", triage.getTriageStatus()); rows.add(row);
            counts.put(state, counts.getOrDefault(state, 0L) + 1);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", source); result.put("verification", target); result.put("history", history);
        result.put("items", rows); result.put("counts", counts); return result;
    }

    String status(FindingView original, FindingView triage, ScanRun source, ScanRun target,
                  List<FindingView> current, Set<String> covered) {
        if ("FIXED".equals(original.getResultStatus())) return "BASELINE_FIXED";
        if (target == null) return "FIXED_PENDING".equals(triage.getTriageStatus()) ? "PENDING_VERIFICATION" : "TODO";
        if (target.getStatus() != ScanRunStatus.COMPLETED || target.getScanMode() != ScanMode.FULL
                || !Objects.equals(source.getProjectId(), target.getProjectId())
                || !Objects.equals(source.getRuleProfile(), target.getRuleProfile())
                || !covered.contains(normalize(original.getScanner()))) return "UNVERIFIED";
        if (current.stream().anyMatch(f -> original.getFingerprint() != null
                && original.getFingerprint().equals(f.getFingerprint()))) return "STILL_PRESENT";
        // Changed vulnerable code can change the fingerprint. A same-rule hit elsewhere in the file
        // is deliberately sent to manual review rather than declared fixed.
        if (current.stream().anyMatch(f -> sameRuleLocation(original, f))) return "NEEDS_REVIEW";
        return "NOT_DETECTED";
    }

    private boolean sameRuleLocation(FindingView a, FindingView b) {
        return normalize(a.getScanner()).equals(normalize(b.getScanner()))
                && path(a.getFilePath()).equals(path(b.getFilePath()))
                && family(a.getRuleId()).equals(family(b.getRuleId()));
    }
    private String family(String rule) {
        String family = fingerprints.semanticFamily(rule);
        return family == null ? normalize(rule) : family;
    }
    private String path(String value) { return value == null ? "" : value.replace('\\', '/').replaceFirst("^\\./", ""); }
    private String normalize(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
}
