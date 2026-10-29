package io.codesentry.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.ai.dto.AiReviewDecision;
import io.codesentry.ai.dto.AiReviewConfigurationView;
import io.codesentry.ai.dto.AiReviewEvidence;
import io.codesentry.ai.dto.AiReviewSummary;
import io.codesentry.ai.mapper.AiReviewMapper;
import io.codesentry.ai.model.AiReviewJob;
import io.codesentry.ai.model.AiReviewRecord;
import io.codesentry.ai.provider.AiReviewProvider;
import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.finding.model.FindingView;
import io.codesentry.finding.service.FindingPersistenceService;
import io.codesentry.scan.model.ScanRun;
import io.codesentry.scan.model.ScanRunStatus;
import io.codesentry.scan.service.ScanRunService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AiReviewService {
    private final io.codesentry.process.CancellableTasks tasks = new io.codesentry.process.CancellableTasks();
    public static final String PROMPT_VERSION = "codesentry-ai-review-v2";
    private final AiReviewMapper mapper;
    private final FindingPersistenceService findingService;
    private final ScanRunService scanRunService;
    private final AiReviewEvidenceBuilder evidenceBuilder;
    private final AiReviewProvider provider;
    private final CodeSentryProperties properties;
    private final ObjectMapper objectMapper;
    private final TaskExecutor taskExecutor;

    public AiReviewService(AiReviewMapper mapper, FindingPersistenceService findingService,
                           ScanRunService scanRunService, AiReviewEvidenceBuilder evidenceBuilder,
                           AiReviewProvider provider, CodeSentryProperties properties, ObjectMapper objectMapper,
                           @Qualifier("aiReviewTaskExecutor") TaskExecutor taskExecutor) {
        this.mapper = mapper;
        this.findingService = findingService;
        this.scanRunService = scanRunService;
        this.evidenceBuilder = evidenceBuilder;
        this.provider = provider;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.taskExecutor = taskExecutor;
    }

    public AiReviewSummary reviewFinding(Long findingId) {
        ensureEnabled();
        FindingView finding = findingService.requireLatest(findingId);
        AiReviewJob job = createJob(finding.getScanRunId(), 1);
        tasks.registerCurrent(job.getId());
        try {
            mapper.markJobRunning(job.getId(), LocalDateTime.now());
            AiReviewSummary summary = executeFinding(finding, job.getId());
            mapper.updateJobProgress(job.getId(), 1, 0);
            mapper.finishJob(job.getId(), "COMPLETED", null, LocalDateTime.now());
            return summary;
        } catch (RuntimeException exception) {
            io.codesentry.process.CancellableTasks.check();
            mapper.updateJobProgress(job.getId(), 0, 1);
            mapper.finishJob(job.getId(), "FAILED", safeMessage(exception), LocalDateTime.now());
            throw exception;
        } finally {
            tasks.unregisterCurrent(job.getId());
        }
    }

    public AiReviewJob startRunReview(Long scanRunId) {
        return startRunReview(scanRunId, false);
    }

    public AiReviewJob retryFailedRunReview(Long scanRunId) {
        return startRunReview(scanRunId, true);
    }

    private AiReviewJob startRunReview(Long scanRunId, boolean failedOnly) {
        ensureEnabled();
        ScanRun run = scanRunService.requireById(scanRunId);
        if (run.getStatus() != ScanRunStatus.COMPLETED && run.getStatus() != ScanRunStatus.PARTIAL) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "只有已完成或部分完成的扫描批次可以执行 AI 审核");
        }
        AiReviewJob active = mapper.findLatestJobByRunId(scanRunId);
        if (active != null && ("PENDING".equals(active.getStatus()) || "RUNNING".equals(active.getStatus()))) {
            return active;
        }
        List<FindingView> findings = reviewableFindings(scanRunId);
        if (failedOnly) {
            java.util.Set<Long> failedIds = new java.util.HashSet<>();
            for (AiReviewSummary summary : latestForRun(scanRunId)) {
                if ("FAILED".equals(summary.getStatus())) failedIds.add(summary.getFindingId());
            }
            findings.removeIf(finding -> !failedIds.contains(finding.getId()));
        }
        if (findings.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "当前批次没有等待 AI 审核的问题");
        }
        AiReviewJob job = createJob(scanRunId, findings.size());
        try {
            tasks.submit(job.getId(), taskExecutor, () -> executeJob(job.getId(), findings));
        } catch (RuntimeException exception) {
            mapper.finishJob(job.getId(), "FAILED", "AI 审核任务队列已满", LocalDateTime.now());
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AI 审核任务队列已满，请稍后重试");
        }
        return job;
    }

    private List<FindingView> reviewableFindings(Long scanRunId) {
        List<FindingView> result = new ArrayList<>();
        for (FindingView finding : findingService.findByRunId(scanRunId)) {
            if (!"FIXED".equals(finding.getResultStatus())
                    && !"FALSE_POSITIVE".equals(finding.getTriageStatus())) result.add(finding);
        }
        return result;
    }

    private void executeJob(Long jobId, List<FindingView> findings) {
        checkJobActive(jobId);
        mapper.markJobRunning(jobId, LocalDateTime.now());
        int completed = 0;
        int failed = 0;
        List<FindingView> retryFindings = new ArrayList<>();
        for (FindingView finding : findings) {
            checkJobActive(jobId);
            try {
                executeFinding(finding, jobId);
                completed++;
            } catch (Exception exception) {
                if (exception instanceof java.util.concurrent.CancellationException) return;
                io.codesentry.process.CancellableTasks.check();
                failed++;
                retryFindings.add(finding);
            }
            mapper.updateJobProgress(jobId, completed, failed);
        }
        // Finish the first pass before retrying. Successful findings are never called again.
        if (!retryFindings.isEmpty()) {
            checkJobActive(jobId);
            failed = 0;
            mapper.updateJobProgress(jobId, completed, failed);
            for (FindingView finding : retryFindings) {
                checkJobActive(jobId);
                try {
                    executeFinding(finding, jobId);
                    completed++;
                } catch (Exception exception) {
                    if (exception instanceof java.util.concurrent.CancellationException) return;
                    io.codesentry.process.CancellableTasks.check();
                    failed++;
                }
                mapper.updateJobProgress(jobId, completed, failed);
            }
        }
        String status = failed == 0 ? "COMPLETED" : completed == 0 ? "FAILED" : "PARTIAL";
        mapper.finishJob(jobId, status, failed == 0 ? null : failed + " 个问题 AI 审核失败", LocalDateTime.now());
    }

    private AiReviewSummary executeFinding(FindingView finding, Long jobId) {
        checkJobActive(jobId);
        AiReviewEvidence evidence = evidenceBuilder.build(finding);
        String serialized = evidenceBuilder.serialize(evidence);
        String inputHash = evidenceBuilder.sha256(serialized);
        try {
            AiReviewDecision primary = provider.review(evidence, "PRIMARY", null);
            checkJobActive(jobId);
            AiReviewRecord primaryRecord = saveDecision(finding, jobId, "PRIMARY", primary, inputHash);
            AiReviewDecision verification = provider.review(evidence, "VERIFY", primary);
            checkJobActive(jobId);
            AiReviewRecord verificationRecord = saveDecision(finding, jobId, "VERIFY", verification, inputHash);
            return summarize(primaryRecord, verificationRecord);
        } catch (RuntimeException exception) {
            if (exception instanceof java.util.concurrent.CancellationException) throw exception;
            checkJobActive(jobId);
            saveFailure(finding, jobId, inputHash, exception);
            throw exception;
        }
    }

    private void checkJobActive(Long jobId) {
        io.codesentry.process.CancellableTasks.check();
        AiReviewJob job = mapper.findJobById(jobId);
        if (job != null && "CANCELLED".equals(job.getStatus())) {
            throw new java.util.concurrent.CancellationException("AI 审核已停止");
        }
    }

    private AiReviewRecord saveDecision(FindingView finding, Long jobId, String round,
                                        AiReviewDecision decision, String inputHash) {
        AiReviewRecord record = baseRecord(finding, jobId, round, inputHash);
        record.setVerdict(decision.getVerdict());
        record.setConfidence(BigDecimal.valueOf(decision.getConfidence()).setScale(4, RoundingMode.HALF_UP));
        record.setReason(limit(evidenceBuilder.sanitize(decision.getReason()), 4000));
        record.setEvidenceLines(json(decision.getEvidenceLines()));
        record.setMissingEvidence(json(sanitizedList(decision.getMissingEvidence())));
        record.setRecommendation(limit(evidenceBuilder.sanitize(decision.getRecommendation()), 4000));
        record.setStatus("COMPLETED");
        mapper.insertReview(record);
        return record;
    }

    private void saveFailure(FindingView finding, Long jobId, String inputHash, RuntimeException exception) {
        AiReviewRecord record = baseRecord(finding, jobId, "ERROR", inputHash);
        record.setVerdict("AI_ERROR");
        record.setConfidence(BigDecimal.ZERO);
        record.setStatus("FAILED");
        record.setErrorMessage(safeMessage(exception));
        mapper.insertReview(record);
    }

    private AiReviewRecord baseRecord(FindingView finding, Long jobId, String round, String inputHash) {
        AiReviewRecord record = new AiReviewRecord();
        record.setProjectId(finding.getProjectId());
        record.setScanRunId(finding.getScanRunId());
        record.setFindingId(finding.getId());
        record.setReviewJobId(jobId);
        record.setReviewRound(round);
        record.setProvider(provider.providerName());
        record.setModel(provider.modelName());
        record.setInputHash(inputHash);
        record.setPromptVersion(PROMPT_VERSION);
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }

    private AiReviewJob createJob(Long scanRunId, int total) {
        AiReviewJob job = new AiReviewJob();
        job.setScanRunId(scanRunId);
        job.setProvider(provider.providerName());
        job.setModel(provider.modelName());
        job.setStatus("PENDING");
        job.setTotalFindings(total);
        job.setCompletedFindings(0);
        job.setFailedFindings(0);
        job.setCreatedAt(LocalDateTime.now());
        mapper.insertJob(job);
        return job;
    }

    public AiReviewSummary latestForFinding(Long findingId) {
        return latestSummary(mapper.findByFindingId(findingId));
    }

    public List<AiReviewSummary> latestForRun(Long scanRunId) {
        Map<Long, List<AiReviewRecord>> grouped = new LinkedHashMap<>();
        for (AiReviewRecord record : mapper.findByRunId(scanRunId)) {
            grouped.computeIfAbsent(record.getFindingId(), ignored -> new ArrayList<>()).add(record);
        }
        List<AiReviewSummary> summaries = new ArrayList<>();
        grouped.values().forEach(records -> {
            AiReviewSummary summary = latestSummary(records);
            if (summary != null) summaries.add(summary);
        });
        return summaries;
    }

    public AiReviewJob requireJob(Long jobId) {
        AiReviewJob job = mapper.findJobById(jobId);
        if (job == null) throw new BusinessException(HttpStatus.NOT_FOUND, "AI 审核任务不存在");
        return job;
    }

    public AiReviewJob cancelJob(Long jobId) {
        requireJob(jobId);
        mapper.cancelJob(jobId, LocalDateTime.now());
        tasks.cancel(jobId);
        return requireJob(jobId);
    }

    public List<AiReviewRecord> recordsForJob(Long jobId) {
        requireJob(jobId);
        return mapper.findByJobId(jobId);
    }

    public List<FindingView> findingsForJob(Long jobId) {
        return findingService.findByRunId(requireJob(jobId).getScanRunId());
    }

    public AiReviewJob latestJobForRun(Long scanRunId) {
        scanRunService.requireById(scanRunId);
        return mapper.findLatestJobByRunId(scanRunId);
    }

    public AiReviewConfigurationView configuration() {
        return new AiReviewConfigurationView(properties.getAi().isEnabled(), provider.providerName(),
                properties.getAi().isEnabled() ? provider.modelName() : null);
    }

    private AiReviewSummary latestSummary(List<AiReviewRecord> records) {
        if (records == null || records.isEmpty()) return null;
        Long latestJobId = records.get(0).getReviewJobId();
        AiReviewRecord primary = records.stream().filter(item -> Objects.equals(latestJobId, item.getReviewJobId()))
                .filter(item -> "PRIMARY".equals(item.getReviewRound())).findFirst().orElse(null);
        // Records arrive newest first. Never pair a new primary with an earlier attempt's verification.
        AiReviewRecord verify = records.stream().filter(item -> Objects.equals(latestJobId, item.getReviewJobId()))
                .filter(item -> primary == null || primary.getId() == null || item.getId() == null || item.getId() > primary.getId())
                .filter(item -> "VERIFY".equals(item.getReviewRound())).findFirst().orElse(null);
        AiReviewRecord error = records.stream().filter(item -> Objects.equals(latestJobId, item.getReviewJobId()))
                .filter(item -> "ERROR".equals(item.getReviewRound())).findFirst().orElse(null);
        if ("ERROR".equals(records.get(0).getReviewRound()) || primary == null || verify == null) {
            AiReviewSummary failed = new AiReviewSummary();
            failed.setFindingId(records.get(0).getFindingId());
            failed.setScanRunId(records.get(0).getScanRunId());
            AiReviewJob job = mapper.findJobById(latestJobId);
            if (job != null && "CANCELLED".equals(job.getStatus()) && error == null) {
                failed.setPrimaryReview(primary);
                failed.setVerificationReview(verify);
                failed.setConsolidatedVerdict("CANCELLED");
                failed.setStatus("CANCELLED");
                failed.setErrorMessage("审核已停止，双轮尚未完成");
                return failed;
            }
            failed.setPrimaryReview(primary);
            failed.setVerificationReview(verify);
            failed.setConsolidatedVerdict("AI_ERROR");
            failed.setStatus("FAILED");
            failed.setErrorMessage(error == null ? "AI 双轮审核未完整执行" : error.getErrorMessage());
            return failed;
        }
        return summarize(primary, verify);
    }

    private AiReviewSummary summarize(AiReviewRecord primary, AiReviewRecord verify) {
        AiReviewSummary summary = new AiReviewSummary();
        summary.setFindingId(primary.getFindingId());
        summary.setScanRunId(primary.getScanRunId());
        summary.setPrimaryReview(primary);
        summary.setVerificationReview(verify);
        boolean disagreement = !Objects.equals(primary.getVerdict(), verify.getVerdict());
        double confidence = Math.min(primary.getConfidence().doubleValue(), verify.getConfidence().doubleValue());
        boolean insufficient = "INSUFFICIENT_EVIDENCE".equals(primary.getVerdict())
                || "INSUFFICIENT_EVIDENCE".equals(verify.getVerdict());
        summary.setDisagreement(disagreement);
        summary.setConfidence(confidence);
        summary.setConfidenceAvailable(true);
        summary.setHighConfidence(!disagreement && !insufficient
                && confidence * 100 >= properties.getAi().getHighConfidencePercent());
        summary.setConsolidatedVerdict(disagreement ? "NEEDS_HUMAN"
                : insufficient ? "INSUFFICIENT_EVIDENCE" : primary.getVerdict());
        summary.setStatus(disagreement || insufficient ? "NEEDS_HUMAN" : "COMPLETED");
        return summary;
    }

    private void ensureEnabled() {
        if (!properties.getAi().isEnabled()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "AI 审核功能未启用");
        }
        if (!StringUtils.hasText(properties.getAi().getBaseUrl()) || !StringUtils.hasText(properties.getAi().getModel())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "AI 审核配置不完整");
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { return "[]"; }
    }
    private List<String> sanitizedList(List<String> values) {
        List<String> result = new ArrayList<>();
        if (values != null) values.forEach(value -> result.add(limit(evidenceBuilder.sanitize(value), 500)));
        return result;
    }
    private String limit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }
    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return limit(message == null || message.isBlank() ? exception.getClass().getSimpleName() : message, 500);
    }
}
