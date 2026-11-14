package io.codesentry.finding.dto;

import java.util.List;

public class BatchTriageResult {
  private final int requested;
  private final int matched;
  private final int unmatched;
  private final List<Long> matchedFindingIds;
  private final List<String> unmatchedSelectors;

  public BatchTriageResult(
      int requested, int matched, List<Long> matchedFindingIds, List<String> unmatchedSelectors) {
    this.requested = requested;
    this.matched = matched;
    this.unmatched = unmatchedSelectors.size();
    this.matchedFindingIds = matchedFindingIds;
    this.unmatchedSelectors = unmatchedSelectors;
  }

  public int getRequested() {
    return requested;
  }

  public int getMatched() {
    return matched;
  }

  public int getUnmatched() {
    return unmatched;
  }

  public List<Long> getMatchedFindingIds() {
    return matchedFindingIds;
  }

  public List<String> getUnmatchedSelectors() {
    return unmatchedSelectors;
  }
}
