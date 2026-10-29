package io.codesentry.finding.controller;

import io.codesentry.finding.model.FindingView;
import io.codesentry.finding.service.FindingPersistenceService;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FindingControllerTest {
    @Test void ledgerReturnsAllCandidatesWithoutRequiringAiOrFilteringHumanDecisions() {
        FindingPersistenceService raw = mock(FindingPersistenceService.class);
        FindingController controller = new FindingController(raw);
        FindingView pending = new FindingView(); pending.setId(1L);
        FindingView falsePositive = new FindingView(); falsePositive.setId(2L);
        falsePositive.setTriageStatus("FALSE_POSITIVE");
        List<FindingView> candidates = List.of(pending, falsePositive);
        when(raw.findByRunId(20L)).thenReturn(candidates);
        when(raw.findRecent(100)).thenReturn(candidates);
        assertSame(candidates, controller.byScanRun(20L).getData());
        assertSame(candidates, controller.recent(100).getData());
        verify(raw).findByRunId(20L);
        verify(raw).findRecent(100);
        verifyNoMoreInteractions(raw);
        assertNull(pending.getTriageStatus());
        assertEquals("FALSE_POSITIVE", falsePositive.getTriageStatus());
    }
}
