package io.codesentry.report.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.finding.model.FindingView;
import io.codesentry.report.mapper.ReportSnapshotMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ReportSnapshotStoreTest {
  @Test
  void firstArchiveWinsAndEmptyArchiveIsDifferentFromNoArchive() {
    ReportSnapshotMapper mapper = mock(ReportSnapshotMapper.class);
    AtomicReference<String> stored = new AtomicReference<>();
    when(mapper.find(1L)).thenAnswer(c -> stored.get());
    when(mapper.insert(eq(1L), anyString()))
        .thenAnswer(c -> stored.compareAndSet(null, c.getArgument(1)) ? 1 : 0);
    ReportSnapshotStore store =
        new ReportSnapshotStore(mapper, new ObjectMapper().findAndRegisterModules());
    assertNull(store.read(1L));
    assertNull(store.readIds(1L));
    assertNull(store.readSummary(1L));
    FindingView f = new FindingView();
    f.setId(42L);
    f.setTitle("原报告问题");
    assertEquals(42L, store.freeze(1L, List.of(f)).get(0).getId());
    assertEquals("原报告问题", store.freeze(1L, List.of()).get(0).getTitle());
    when(mapper.find(2L)).thenReturn("[]");
    assertTrue(store.read(2L).isEmpty());
    assertEquals(List.of(42L), store.readIds(1L));
    assertEquals(List.of(), store.readIds(2L));
    assertTrue(store.readSummary(2L).isEmpty());
    assertEquals(42L, store.readSummary(1L).get(0).getId());
    when(mapper.find(3L))
        .thenReturn("[{\"id\":7,\"evidence\":{\"id\":900},\"text\":\"large evidence\"}]");
    assertEquals(List.of(7L), store.readIds(3L));
    when(mapper.find(4L)).thenReturn("[{\"id\":7");
    assertThrows(IllegalStateException.class, () -> store.readIds(4L));
  }
}
