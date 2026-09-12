package io.codesentry.finding.service;

import io.codesentry.finding.mapper.ScanFindingMapper;
import io.codesentry.finding.dto.TriageFindingRequest;
import io.codesentry.finding.dto.BatchTriageFindingRequest;
import io.codesentry.finding.dto.BatchTriageResult;
import io.codesentry.finding.model.FindingView;
import io.codesentry.finding.model.ScanFinding;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.scan.engine.NormalizedFinding;
import io.codesentry.scan.engine.SourceSnippetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import io.codesentry.common.error.BusinessException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

@Service
public class FindingPersistenceService {

    private final ScanFindingMapper mapper;
    private final CodeSentryProperties properties;

    public FindingPersistenceService(ScanFindingMapper mapper, CodeSentryProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    @Transactional
    public void save(Long projectId, Long scanRunId, NormalizedFinding source, String resultStatus) {
        LocalDateTime now = LocalDateTime.now();
        ScanFinding finding = new ScanFinding();
        finding.setProjectId(projectId);
        finding.setFingerprint(source.getFingerprint());
        finding.setScanner(source.getScanner());
        finding.setRuleId(source.getRuleId() == null ? "unknown" : source.getRuleId());
        finding.setCategory(source.getCategory());
        finding.setSeverity(source.getSeverity().name());
        finding.setTitle(source.getTitle() == null ? source.getRuleId() : source.getTitle());
        finding.setDescription(source.getDescription());
        finding.setRecommendation(source.getRecommendation());
        finding.setCwe(source.getCwe());
        finding.setFirstSeenAt(now);
        finding.setLastSeenAt(now);
        mapper.upsert(finding);
        mapper.insertInstance(finding.getId(), scanRunId,
                source.getFilePath() == null ? "unknown" : source.getFilePath(),
                source.getStartLine(), source.getEndLine(), source.getCodeSnippet(), resultStatus, now);
    }

    public List<FindingView> findByRunId(Long runId) {
        return enrichSnippets(mapper.findByRunId(runId));
    }

    public List<FindingView> findRecent(int limit) {
        return enrichSnippets(mapper.findRecent(Math.min(Math.max(limit, 1), 500)));
    }

    public List<FindingView> findVisibleByRunId(Long runId) {
        return excludeFalsePositives(findByRunId(runId));
    }

    public List<FindingView> findVisibleRecent(int limit) {
        return excludeFalsePositives(findRecent(limit));
    }

    public FindingView requireLatest(Long findingId) {
        FindingView finding = mapper.findLatestByFindingId(findingId);
        if (finding == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "待复核问题不存在");
        }
        return enrichSnippet(finding);
    }

    @Transactional
    public FindingView triage(Long findingId, TriageFindingRequest request) {
        FindingView finding = requireLatest(findingId);
        mapper.upsertTriage(finding.getProjectId(), findingId, request.getTriageStatus(),
                request.getComment(), request.getReviewer().trim(), request.getExpiresAt());
        return enrichSnippet(mapper.findLatestByFindingId(findingId));
    }

    @Transactional
    public BatchTriageResult batchTriage(Long runId, BatchTriageFindingRequest request) {
        List<FindingView> findings = mapper.findByRunId(runId);
        List<Long> matchedIds = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        for (BatchTriageFindingRequest.Selector selector : request.getSelectors()) {
            FindingView match = findings.stream().filter(finding -> matches(finding, selector)).findFirst().orElse(null);
            if (match == null) {
                unmatched.add(selector.describe());
                continue;
            }
            mapper.upsertTriage(match.getProjectId(), match.getId(), request.getTriageStatus(),
                    request.getComment(), request.getReviewer().trim(), request.getExpiresAt());
            matchedIds.add(match.getId());
        }
        return new BatchTriageResult(request.getSelectors().size(), matchedIds.size(), matchedIds, unmatched);
    }

    private boolean matches(FindingView finding, BatchTriageFindingRequest.Selector selector) {
        return value(finding.getScanner()).equalsIgnoreCase(value(selector.getScanner()))
                && value(finding.getRuleId()).equals(value(selector.getRuleId()))
                && normalizePath(finding.getFilePath()).equals(normalizePath(selector.getFilePath()))
                && selector.getStartLine().equals(finding.getStartLine())
                && (selector.getEndLine() == null || selector.getEndLine().equals(finding.getEndLine()));
    }

    private String value(String value) { return value == null ? "" : value.trim(); }

    private String normalizePath(String value) {
        return value(value).replace('\\', '/').replaceFirst("^\\./", "");
    }

    private List<FindingView> excludeFalsePositives(List<FindingView> findings) {
        return findings.stream()
                .filter(finding -> !"FALSE_POSITIVE".equals(finding.getTriageStatus()))
                .collect(Collectors.toList());
    }

    private List<FindingView> enrichSnippets(List<FindingView> findings) {
        findings.forEach(this::enrichSnippet);
        return findings;
    }

    private FindingView enrichSnippet(FindingView finding) {
        if (finding != null && "Semgrep".equals(finding.getScanner())) {
            finding.setCodeSnippet(SourceSnippetExtractor.extract(properties.getStorage().rootPath(),
                    finding.getFilePath(), finding.getStartLine(), finding.getEndLine(), finding.getCodeSnippet()));
        }
        return finding;
    }
}
