package io.codesentry.finding.service;

import io.codesentry.config.CodeSentryProperties;
import io.codesentry.finding.dto.TriageFindingRequest;
import io.codesentry.finding.dto.BatchTriageFindingRequest;
import io.codesentry.finding.dto.BatchTriageResult;
import io.codesentry.finding.mapper.ScanFindingMapper;
import io.codesentry.finding.model.FindingView;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.List;

class FindingPersistenceServiceTest {

    @Test
    void triagePersistsManualConclusionAndReturnsLatestView() {
        ScanFindingMapper mapper = mock(ScanFindingMapper.class);
        FindingPersistenceService service = new FindingPersistenceService(mapper, new CodeSentryProperties());
        FindingView before = new FindingView();
        before.setId(18L);
        before.setProjectId(3L);
        FindingView after = new FindingView();
        after.setId(18L);
        after.setProjectId(3L);
        after.setTriageStatus("ACCEPTED_RISK");
        when(mapper.findLatestByFindingId(18L)).thenReturn(before, after);

        TriageFindingRequest request = new TriageFindingRequest();
        request.setTriageStatus("ACCEPTED_RISK");
        request.setReviewer(" 张三 ");
        request.setComment("业务补偿措施已确认");
        request.setExpiresAt(LocalDateTime.of(2026, 12, 31, 18, 0));

        FindingView result = service.triage(18L, request);

        assertEquals("ACCEPTED_RISK", result.getTriageStatus());
        verify(mapper).upsertTriage(3L, 18L, "ACCEPTED_RISK", "业务补偿措施已确认", "张三",
                LocalDateTime.of(2026, 12, 31, 18, 0));
    }

    @Test
    void batchTriageMatchesNormalizedExactSelectorsAndReportsMisses() {
        ScanFindingMapper mapper = mock(ScanFindingMapper.class);
        FindingPersistenceService service = new FindingPersistenceService(mapper, new CodeSentryProperties());
        FindingView finding = new FindingView();
        finding.setId(27L);
        finding.setProjectId(3L);
        finding.setScanner("Semgrep");
        finding.setRuleId("rule-x");
        finding.setFilePath("src\\main\\Example.java");
        finding.setStartLine(42);
        finding.setEndLine(44);
        when(mapper.findByRunId(7L)).thenReturn(List.of(finding));

        BatchTriageFindingRequest.Selector match = new BatchTriageFindingRequest.Selector();
        match.setScanner("semgrep");
        match.setRuleId("rule-x");
        match.setFilePath("./src/main/Example.java");
        match.setStartLine(42);
        match.setEndLine(44);
        BatchTriageFindingRequest.Selector miss = new BatchTriageFindingRequest.Selector();
        miss.setScanner("Semgrep");
        miss.setRuleId("rule-x");
        miss.setFilePath("src/main/Other.java");
        miss.setStartLine(9);

        BatchTriageFindingRequest request = new BatchTriageFindingRequest();
        request.setTriageStatus("FALSE_POSITIVE");
        request.setReviewer("  张三 ");
        request.setComment("固定库");
        request.setSelectors(List.of(match, miss));

        BatchTriageResult result = service.batchTriage(7L, request);

        assertEquals(2, result.getRequested());
        assertEquals(1, result.getMatched());
        assertEquals(1, result.getUnmatched());
        assertEquals(List.of(27L), result.getMatchedFindingIds());
        verify(mapper).upsertTriage(3L, 27L, "FALSE_POSITIVE", "固定库", "张三", null);
    }

    @Test
    void visibleQueriesExcludeOnlyConfirmedFalsePositives() {
        ScanFindingMapper mapper = mock(ScanFindingMapper.class);
        FindingPersistenceService service = new FindingPersistenceService(mapper, new CodeSentryProperties());
        FindingView confirmed = new FindingView();
        confirmed.setId(31L);
        confirmed.setTriageStatus("CONFIRMED");
        FindingView falsePositive = new FindingView();
        falsePositive.setId(32L);
        falsePositive.setTriageStatus("FALSE_POSITIVE");
        FindingView pending = new FindingView();
        pending.setId(33L);
        when(mapper.findByRunId(8L)).thenReturn(List.of(confirmed, falsePositive, pending));

        List<FindingView> visible = service.findVisibleByRunId(8L);

        assertEquals(List.of(31L, 33L), visible.stream().map(FindingView::getId).collect(java.util.stream.Collectors.toList()));
    }
}
