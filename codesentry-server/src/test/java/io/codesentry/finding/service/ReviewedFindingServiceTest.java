package io.codesentry.finding.service;

import io.codesentry.ai.service.AiReviewService;
import io.codesentry.finding.model.FindingView;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewedFindingServiceTest {
    @Test void unreviewedItemsAreHiddenButRawPipelineIsNotModified() {
        FindingPersistenceService raw = mock(FindingPersistenceService.class);
        AiReviewService ai = mock(AiReviewService.class);
        FindingView f = new FindingView(); f.setId(1L); f.setScanRunId(2L);
        when(raw.findByRunId(2L)).thenReturn(List.of(f)); when(raw.findRecent(100)).thenReturn(List.of(f));
        when(ai.latestForRun(2L)).thenReturn(List.of());
        ReviewedFindingService reviewed = new ReviewedFindingService(raw, ai);
        assertTrue(reviewed.byRun(2L).isEmpty()); assertTrue(reviewed.recent(100).isEmpty());
        assertEquals(1, raw.findByRunId(2L).size()); verify(ai, times(2)).latestForRun(2L); verifyNoMoreInteractions(ai);
    }
}
