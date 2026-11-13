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
import java.util.Locale;
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
        String sourceTitle = source.getTitle() == null ? source.getRuleId() : source.getTitle();
        finding.setTitle(limit(sourceTitle == null ? "unknown" : sourceTitle, 512));
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
        return enrichSnippets(findStoredByRunId(runId));
    }

    /** 报告先按审核政策筛选，再为采纳项回读源码；未采纳项不访问文件系统。 */
    /** 审核规划保留全部原始命中，以便审计已被报告规则排除的线索。 */
    public List<FindingView> findAuditInputsByRunId(Long runId) { return mapper.findByRunId(runId); }

    public List<FindingView> findStoredByRunId(Long runId) {
        return excludeSuppressedRules(mapper.findByRunId(runId));
    }

    /** 台账仅需报告采纳身份；保持报告的规则过滤和原始顺序，不回读源码快照。 */
    public List<FindingView> findPolicyByRunId(Long runId) {
        return excludeSuppressedRules(mapper.findPolicyByRunId(runId));
    }

    public List<FindingView> findRecent(int limit) {
        return enrichSnippets(excludeSuppressedRules(mapper.findRecent(Math.min(Math.max(limit, 1), 500))));
    }

    public List<FindingView> findClassifiedRecent(int limit,String type) {
        if(!java.util.Set.of("QUALITY","SECURITY","AUDIT").contains(type))throw new BusinessException(HttpStatus.BAD_REQUEST,"问题分类无效");
        return enrichSnippets(excludeSuppressedRules(mapper.findClassifiedRecent(Math.min(500,Math.max(1,limit)),type)));
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
        if (selector.getFindingId() != null) return selector.getFindingId().equals(finding.getId());
        return value(finding.getScanner()).equalsIgnoreCase(value(selector.getScanner()))
                && value(finding.getRuleId()).equals(value(selector.getRuleId()))
                && normalizePath(finding.getFilePath()).equals(normalizePath(selector.getFilePath()))
                && selector.getStartLine().equals(finding.getStartLine())
                && (selector.getEndLine() == null || selector.getEndLine().equals(finding.getEndLine()));
    }

    private String value(String value) { return value == null ? "" : value.trim(); }

    private String limit(String value, int maximum) {
        if (value == null || value.length() <= maximum) return value;
        return value.substring(0, Math.max(0, maximum - 1)) + "…";
    }

    private String normalizePath(String value) {
        return value(value).replace('\\', '/').replaceFirst("^\\./", "");
    }

    private List<FindingView> excludeFalsePositives(List<FindingView> findings) {
        return findings.stream()
                .filter(finding -> !"FALSE_POSITIVE".equals(finding.getTriageStatus()))
                .collect(Collectors.toList());
    }

    private List<FindingView> excludeSuppressedRules(List<FindingView> findings) {
        return findings.stream().filter(finding -> {
            String rule = value(finding.getRuleId()).toLowerCase(Locale.ROOT);
            return !"codesentry.javascript.eval".equals(rule) && !rule.contains("eval-detected");
        }).collect(Collectors.toList());
    }

    /** 同一请求只读取一次相同源文件，避免大报告为数千条同文件命中反复读取全文。 */
    public List<FindingView> enrichSnippets(List<FindingView> findings) {
        SourceSnippetExtractor.ReadSession session = new SourceSnippetExtractor.ReadSession();
        findings.forEach(finding -> enrichSnippet(finding,session));
        return findings;
    }

    public FindingView enrichSnippet(FindingView finding) {
        return enrichSnippet(finding,null);
    }

    private FindingView enrichSnippet(FindingView finding, SourceSnippetExtractor.ReadSession session) {
        if (finding != null) {
            String fallback = finding.getCodeSnippet();
            String snippet = SourceSnippetExtractor.extract(properties.getStorage().rootPath(),
                    finding.getFilePath(), finding.getStartLine(), finding.getEndLine(), fallback,session);
            if (isUnavailable(snippet, fallback) && finding.getScanRunId() != null
                    && finding.getFilePath() != null && !java.nio.file.Path.of(finding.getFilePath()).isAbsolute()) {
                java.nio.file.Path snapshot = properties.getStorage().rootPath()
                        .resolve(properties.getStorage().getSnapshotsDirectory())
                        .resolve(String.valueOf(finding.getScanRunId())).resolve("current");
                snippet = SourceSnippetExtractor.extract(snapshot, finding.getFilePath(),
                        finding.getStartLine(), finding.getEndLine(), fallback,session);
            }
            finding.setCodeSnippet(snippet);
        }
        return finding;
    }

    private boolean isUnavailable(String snippet, String fallback) {
        return snippet == null || snippet.equals(fallback) || "[REDACTED]".equals(snippet.trim());
    }
}
