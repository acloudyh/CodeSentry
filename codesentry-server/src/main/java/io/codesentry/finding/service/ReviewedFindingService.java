package io.codesentry.finding.service;

import io.codesentry.ai.dto.AiReviewSummary;
import io.codesentry.ai.service.AiReviewService;
import io.codesentry.finding.model.FindingView;
import java.util.*;
import org.springframework.stereotype.Service;

/** Read-only reviewed view; raw evidence remains available to the AI execution pipeline. */
@Service
public class ReviewedFindingService {
  private final FindingPersistenceService findings;
  private final AiReviewService reviews;

  public ReviewedFindingService(FindingPersistenceService findings, AiReviewService reviews) {
    this.findings = findings;
    this.reviews = reviews;
  }

  public List<FindingView> byRun(Long runId) {
    return select(runId, findings.findByRunId(runId));
  }

  public List<FindingView> recent(int limit) {
    List<FindingView> raw = findings.findRecent(limit);
    Map<Long, List<FindingView>> grouped = new LinkedHashMap<>();
    raw.forEach(
        f -> grouped.computeIfAbsent(f.getScanRunId(), ignored -> new ArrayList<>()).add(f));
    List<FindingView> result = new ArrayList<>();
    grouped.forEach((run, items) -> result.addAll(select(run, items)));
    return result;
  }

  private List<FindingView> select(Long runId, List<FindingView> raw) {
    Map<Long, AiReviewSummary> summaries = new LinkedHashMap<>();
    reviews.latestForRun(runId).forEach(s -> summaries.put(s.getFindingId(), s));
    return ReviewedFindingPolicy.select(raw, summaries);
  }
}
