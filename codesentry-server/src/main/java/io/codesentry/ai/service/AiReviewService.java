package io.codesentry.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.ai.dto.AiReviewDecision;
import io.codesentry.ai.dto.AiReviewEvidence;
import io.codesentry.ai.dto.AiReviewSummary;
import io.codesentry.ai.mapper.AiReviewMapper;
import io.codesentry.ai.model.AiReviewJob;
import io.codesentry.ai.model.AiReviewRecord;
import io.codesentry.ai.provider.AiReviewProvider;
import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.finding.model.FindingView;
import io.codesentry.finding.service.DependencyFindingGrouping;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;

@Service
public class AiReviewService {
    private final io.codesentry.process.CancellableTasks tasks = new io.codesentry.process.CancellableTasks();
    public static final String PROMPT_VERSION = "codesentry-ai-review-v3-grouped-dependencies";
    private final AiReviewMapper mapper;
    private final FindingPersistenceService findingService;
    private final ScanRunService scanRunService;
    private final AiReviewEvidenceBuilder evidenceBuilder;
    private final AiReviewProvider provider;
    private final CodeSentryProperties properties;
    private final ObjectMapper objectMapper;
    private final TaskExecutor taskExecutor;
    private final TaskExecutor unitExecutor;
    private final Map<Long, Set<Future<?>>> children = new ConcurrentHashMap<>();

    public AiReviewService(AiReviewMapper mapper, FindingPersistenceService findingService,
                           ScanRunService scanRunService, AiReviewEvidenceBuilder evidenceBuilder,
                           AiReviewProvider provider, CodeSentryProperties properties, ObjectMapper objectMapper,
                           @Qualifier("aiReviewTaskExecutor") TaskExecutor taskExecutor,
                           @Qualifier("aiReviewWorkerExecutor") TaskExecutor unitExecutor) {
        this.mapper = mapper;
        this.findingService = findingService;
        this.scanRunService = scanRunService;
        this.evidenceBuilder = evidenceBuilder;
        this.provider = provider;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.taskExecutor = taskExecutor;
        this.unitExecutor = unitExecutor;
    }

    public AiReviewSummary reviewFinding(Long findingId) {
        ensureEnabled();
        FindingView finding = findingService.requireLatest(findingId);
        ReviewUnit unit = reviewUnits(reviewableFindings(finding.getScanRunId())).stream()
                .filter(item -> item.contains(findingId)).findFirst()
                .orElse(new ReviewUnit(finding));
        AiReviewProvider selectedProvider = snapshotProvider();
        AiReviewJob job;
        synchronized (this) {
            AiReviewJob active = mapper.findLatestJobByRunId(finding.getScanRunId());
            if (active != null && tasks.isActive(active.getId()))
                throw new BusinessException(HttpStatus.CONFLICT, "该批次已有工具审核正在执行，请等待完成或先停止任务");
            job = createJob(finding.getScanRunId(), 1, selectedProvider);
            tasks.registerCurrent(job.getId());
        }
        try {
            mapper.markJobRunning(job.getId(), LocalDateTime.now());
            AiReviewSummary summary = await(submitUnit(job.getId(), () -> executeUnit(unit, job.getId(), findingId, selectedProvider), null));
            mapper.updateJobProgress(job.getId(), 1, 0);
            mapper.finishJob(job.getId(), "COMPLETED", null, LocalDateTime.now());
            return summary;
        } catch (RuntimeException exception) {
            io.codesentry.process.CancellableTasks.check();
            mapper.updateJobProgress(job.getId(), 0, 1);
            mapper.finishJob(job.getId(), "FAILED", safeMessage(exception), LocalDateTime.now());
            throw exception;
        } finally {
            cancelChildren(job.getId());
            tasks.unregisterCurrent(job.getId());
        }
    }

    public AiReviewJob startRunReview(Long scanRunId) {
        return startRunReview(scanRunId, false);
    }

    public AiReviewJob retryFailedRunReview(Long scanRunId) {
        return startRunReview(scanRunId, true);
    }

    private synchronized AiReviewJob startRunReview(Long scanRunId, boolean failedOnly) {
        ensureEnabled();
        ScanRun run = scanRunService.requireById(scanRunId);
        if (run.getStatus() != ScanRunStatus.COMPLETED && run.getStatus() != ScanRunStatus.PARTIAL) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "只有已完成或部分完成的扫描批次可以执行 AI 审核");
        }
        AiReviewJob active = mapper.findLatestJobByRunId(scanRunId);
        if (active != null && ("PENDING".equals(active.getStatus()) || "RUNNING".equals(active.getStatus()))) {
            if (tasks.isActive(active.getId())) return active;
            mapper.finishJob(active.getId(), "FAILED", "服务中断，任务未完整执行", LocalDateTime.now());
        }
        List<FindingView> findings = reviewableFindings(scanRunId);
        List<AiReviewRecord> existingRecords = mapper.findByRunId(scanRunId);
        List<AiReviewSummary> latestReviews = latestForRun(existingRecords);
        if (failedOnly) {
            Set<Long> failedIds = new HashSet<>();
            for (AiReviewSummary summary : latestReviews) {
                if ("FAILED".equals(summary.getStatus())) failedIds.add(summary.getFindingId());
            }
            findings.removeIf(finding -> !failedIds.contains(finding.getId()));
        } else {
            Set<Long> completedIds = new HashSet<>();
            for (AiReviewSummary summary : latestReviews) {
                if (!"FAILED".equals(summary.getStatus()) && !"CANCELLED".equals(summary.getStatus())) {
                    completedIds.add(summary.getFindingId());
                }
            }
            findings.removeIf(finding -> completedIds.contains(finding.getId()));
        }
        if (findings.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "当前批次没有等待工具审核或需要重试的问题");
        }
        List<ReviewUnit> units = reviewUnits(findings);
        AiReviewProvider selectedProvider = snapshotProvider();
        AiReviewJob job = createJob(scanRunId, units.size(), selectedProvider);
        try {
            tasks.submit(job.getId(), taskExecutor, () -> executeJob(job.getId(), units, selectedProvider, job.getConcurrency()));
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

    private void executeJob(Long jobId, List<ReviewUnit> units, AiReviewProvider selectedProvider, int concurrency) {
        int[] counts = {0, 0}; // Only the coordinator updates progress, including retries.
        try {
            checkJobActive(jobId);
            mapper.markJobRunning(jobId, LocalDateTime.now());
            List<ReviewUnit> retry = executePass(jobId, units, selectedProvider, concurrency, counts);
            if (!retry.isEmpty()) {
                checkJobActive(jobId);
                counts[1] = 0;
                mapper.updateJobProgress(jobId, counts[0], 0);
                executePass(jobId, retry, selectedProvider, concurrency, counts);
            }
            checkJobActive(jobId);
            String status = counts[1] == 0 ? "COMPLETED" : counts[0] == 0 ? "FAILED" : "PARTIAL";
            mapper.finishJob(jobId, status, counts[1] == 0 ? null : counts[1] + " 个问题 AI 审核失败", LocalDateTime.now());
        } catch (CancellationException cancelled) {
            // The cancel endpoint owns the terminal state. Never write fake failures.
        } catch (RuntimeException error) {
            if (!Thread.currentThread().isInterrupted())
                mapper.finishJob(jobId, counts[0] == 0 ? "FAILED" : "PARTIAL", safeMessage(error), LocalDateTime.now());
        } finally {
            cancelChildren(jobId);
        }
    }

    private List<ReviewUnit> executePass(Long jobId, List<ReviewUnit> units, AiReviewProvider selectedProvider,
                                         int concurrency, int[] counts) {
        BlockingQueue<FutureTask<ReviewUnit>> completed = new LinkedBlockingQueue<>();
        Map<FutureTask<ReviewUnit>, ReviewUnit> pending = new LinkedHashMap<>();
        List<ReviewUnit> failed = new ArrayList<>();
        int next = 0;
        while (next < units.size() || !pending.isEmpty()) {
            checkJobActive(jobId);
            while (next < units.size() && pending.size() < concurrency) {
                ReviewUnit unit = units.get(next++);
                FutureTask<ReviewUnit> future = submitUnit(jobId, () -> {
                    executeUnit(unit, jobId, unit.primary().getId(), selectedProvider);
                    return unit;
                }, completed);
                pending.put(future, unit);
            }
            FutureTask<ReviewUnit> future;
            try { future = completed.take(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CancellationException("任务已停止"); }
            ReviewUnit unit = pending.remove(future);
            try {
                await(future);
                counts[0]++;
            } catch (CancellationException cancelled) { throw cancelled; }
            catch (RuntimeException error) {
                checkJobActive(jobId);
                counts[1]++;
                failed.add(unit);
            }
            checkJobActive(jobId);
            mapper.updateJobProgress(jobId, counts[0], counts[1]);
        }
        return failed;
    }

    private <T> FutureTask<T> submitUnit(Long jobId, Callable<T> action, BlockingQueue<FutureTask<T>> completed) {
        checkJobActive(jobId);
        FutureTask<T> future = new FutureTask<T>(action) {
            @Override protected void done() {
                Set<Future<?>> active = children.get(jobId);
                if (active != null) active.remove(this);
                if (completed != null) completed.add(this);
            }
        };
        children.computeIfAbsent(jobId, ignored -> ConcurrentHashMap.newKeySet()).add(future);
        try { unitExecutor.execute(future); }
        catch (RuntimeException error) { future.cancel(false); throw error; }
        return future;
    }

    private <T> T await(Future<T> future) {
        try { return future.get(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CancellationException("任务已停止"); }
        catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException("工具审核执行失败", cause);
        }
    }

    private void cancelChildren(Long jobId) {
        Set<Future<?>> active = children.remove(jobId);
        if (active != null) active.forEach(future -> future.cancel(true));
    }

    private AiReviewProvider snapshotProvider() {
        AiReviewProvider selected = provider.snapshot();
        return selected == null ? provider : selected;
    }

    private AiReviewSummary executeUnit(ReviewUnit unit, Long jobId, Long requestedFindingId, AiReviewProvider selectedProvider) {
        checkJobActive(jobId);
        AiReviewEvidence evidence = buildUnitEvidence(unit);
        String serialized = evidenceBuilder.serialize(evidence);
        String inputHash = evidenceBuilder.sha256(serialized);
        try {
            AiReviewDecision primary = selectedProvider.review(evidence, "PRIMARY", null);
            checkJobActive(jobId);
            AiReviewDecision verification = selectedProvider.review(evidence, "VERIFY", primary);
            checkJobActive(jobId);
            AiReviewSummary requested = null;
            for (FindingView member : unit.members) {
                AiReviewRecord primaryRecord = saveDecision(member, jobId, "PRIMARY", primary, inputHash, selectedProvider);
                AiReviewRecord verificationRecord = saveDecision(member, jobId, "VERIFY", verification, inputHash, selectedProvider);
                if (requested == null || Objects.equals(member.getId(), requestedFindingId)) {
                    requested = summarize(primaryRecord, verificationRecord);
                }
            }
            return requested;
        } catch (RuntimeException exception) {
            if (exception instanceof java.util.concurrent.CancellationException) throw exception;
            checkJobActive(jobId);
            for (FindingView member : unit.members) saveFailure(member, jobId, inputHash, exception, selectedProvider);
            throw exception;
        }
    }

    private List<ReviewUnit> reviewUnits(List<FindingView> findings) {
        Map<String, ReviewUnit> grouped = new LinkedHashMap<>();
        for (FindingView finding : findings) {
            String dependencyKey = DependencyFindingGrouping.groupKey(finding);
            String key = dependencyKey == null ? "finding|" + finding.getId() : "dependency|" + dependencyKey;
            grouped.computeIfAbsent(key, ignored -> new ReviewUnit()).members.add(finding);
        }
        return new ArrayList<>(grouped.values());
    }

    private AiReviewEvidence buildUnitEvidence(ReviewUnit unit) {
        AiReviewEvidence evidence = evidenceBuilder.build(unit.primary());
        if (!unit.isGrouped()) return evidence;
        StringBuilder details = new StringBuilder("同一依赖文件与组件版本的多个 CVE，请作为一个整改组统一复核：");
        StringBuilder ruleIds = new StringBuilder();
        for (FindingView member : unit.members) {
            if (ruleIds.length() > 0) ruleIds.append(',');
            ruleIds.append(value(member.getRuleId(), "unknown"));
            details.append("\n- ").append(value(member.getRuleId(), "unknown"))
                    .append("：").append(value(member.getTitle(), "依赖漏洞"));
        }
        evidence.setRuleId(ruleIds.toString());
        evidence.setTitle(DependencyFindingGrouping.componentLabel(unit.primary()) + " 组件 "
                + unit.members.size() + " 个已知漏洞（合并复核）");
        evidence.setDescription(details.toString());
        evidence.setRecommendation("对该依赖组件统一升级到可修复上述 CVE 的安全版本，并完成兼容性验证。");
        return evidence;
    }

    private String value(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private static final class ReviewUnit {
        private final List<FindingView> members = new ArrayList<>();
        private ReviewUnit() {}
        private ReviewUnit(FindingView finding) { members.add(finding); }
        private FindingView primary() { return members.get(0); }
        private boolean isGrouped() { return members.size() > 1; }
        private boolean contains(Long findingId) {
            return members.stream().anyMatch(item -> Objects.equals(item.getId(), findingId));
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
                                        AiReviewDecision decision, String inputHash, AiReviewProvider selectedProvider) {
        AiReviewRecord record = baseRecord(finding, jobId, round, inputHash, selectedProvider);
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

    private void saveFailure(FindingView finding, Long jobId, String inputHash, RuntimeException exception, AiReviewProvider selectedProvider) {
        AiReviewRecord record = baseRecord(finding, jobId, "ERROR", inputHash, selectedProvider);
        record.setVerdict("AI_ERROR");
        record.setConfidence(BigDecimal.ZERO);
        record.setStatus("FAILED");
        record.setErrorMessage(safeMessage(exception));
        mapper.insertReview(record);
    }

    private AiReviewRecord baseRecord(FindingView finding, Long jobId, String round, String inputHash, AiReviewProvider selectedProvider) {
        AiReviewRecord record = new AiReviewRecord();
        record.setProjectId(finding.getProjectId());
        record.setScanRunId(finding.getScanRunId());
        record.setFindingId(finding.getId());
        record.setReviewJobId(jobId);
        record.setReviewRound(round);
        record.setProvider(selectedProvider.providerName());
        record.setModel(selectedProvider.modelName());
        record.setInputHash(inputHash);
        record.setPromptVersion(PROMPT_VERSION);
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }

    private AiReviewJob createJob(Long scanRunId, int total, AiReviewProvider selectedProvider) {
        AiReviewJob job = new AiReviewJob();
        job.setScanRunId(scanRunId);
        job.setProvider(selectedProvider.providerName());
        job.setModel(selectedProvider.modelName());
        job.setStatus("PENDING");
        job.setTotalFindings(total);
        job.setConcurrency(Math.min(total, Math.max(1, Math.min(properties.getAi().getMaxConcurrency(), 8))));
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
        return latestForRun(mapper.findByRunId(scanRunId));
    }

    /** 供列表计算报告采纳，不装载审核长文本，也不逐问题查询未完成任务。 */
    public List<AiReviewSummary> latestPolicyForRun(Long scanRunId) {
        return latestForRun(mapper.findPolicyByRunId(scanRunId), true);
    }

    private List<AiReviewSummary> latestForRun(List<AiReviewRecord> allRecords) {
        return latestForRun(allRecords, false);
    }

    private List<AiReviewSummary> latestForRun(List<AiReviewRecord> allRecords, boolean policyOnly) {
        Map<Long, List<AiReviewRecord>> grouped = new LinkedHashMap<>();
        for (AiReviewRecord record : allRecords) {
            grouped.computeIfAbsent(record.getFindingId(), ignored -> new ArrayList<>()).add(record);
        }
        List<AiReviewSummary> summaries = new ArrayList<>();
        grouped.values().forEach(records -> {
            AiReviewSummary summary = latestSummary(records, policyOnly);
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
        cancelChildren(jobId);
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

    private AiReviewSummary latestSummary(List<AiReviewRecord> records) {
        return latestSummary(records, false);
    }

    private AiReviewSummary latestSummary(List<AiReviewRecord> records, boolean policyOnly) {
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
            // 未完成与取消均不能自动采纳；轻量列表不需要再读取任务描述。
            AiReviewJob job = policyOnly ? null : mapper.findJobById(latestJobId);
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
