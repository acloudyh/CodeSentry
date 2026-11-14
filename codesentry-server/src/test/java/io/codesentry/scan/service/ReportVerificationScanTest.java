package io.codesentry.scan.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.codesentry.common.error.BusinessException;
import io.codesentry.project.model.*;
import io.codesentry.project.service.ScanProjectService;
import io.codesentry.rule.service.RuleProfileService;
import io.codesentry.scan.dto.CreateScanRunRequest;
import io.codesentry.scan.mapper.ScanRunMapper;
import io.codesentry.scan.model.*;
import org.junit.jupiter.api.Test;

class ReportVerificationScanTest {
  @Test
  void locksProjectProfileAndFullModeBeforeEnqueueingAndPersistsReportLink() {
    ScanRunMapper mapper = mock(ScanRunMapper.class);
    ScanProjectService projects = mock(ScanProjectService.class);
    ScanOrchestrator orchestrator = mock(ScanOrchestrator.class);
    ScanRunService service =
        new ScanRunService(
            mapper,
            projects,
            orchestrator,
            mock(ScanToolRunService.class),
            mock(RuleProfileService.class),
            Runnable::run);
    ScanProject project = new ScanProject();
    project.setId(1L);
    project.setSourceType(ProjectSourceType.LOCAL);
    when(projects.requireById(anyLong())).thenReturn(project);
    ScanRun source = new ScanRun();
    source.setId(7L);
    source.setProjectId(1L);
    source.setRuleProfile("LOCAL_OWASP");
    when(mapper.findById(7L)).thenReturn(source);
    CreateScanRunRequest req = new CreateScanRunRequest();
    req.setProjectId(1L);
    req.setSourceReportRunId(7L);
    req.setWorkspaceMode(WorkspaceMode.CURRENT);
    req.setScanMode(ScanMode.FULL);
    req.setRuleProfile("LOCAL_OWASP");
    assertThrows(BusinessException.class, () -> service.start(req));
    when(mapper.hasReportSnapshot(7L)).thenReturn(1);
    req.setScanMode(ScanMode.INCREMENTAL);
    assertThrows(BusinessException.class, () -> service.start(req));
    req.setScanMode(ScanMode.FULL);
    req.setRuleProfile("COMPREHENSIVE");
    assertThrows(BusinessException.class, () -> service.start(req));
    req.setRuleProfile("LOCAL_OWASP");
    source.setProjectId(2L);
    assertThrows(BusinessException.class, () -> service.start(req));
    verify(mapper, never()).insert(any());
    source.setProjectId(1L);
    when(mapper.insert(any()))
        .thenAnswer(
            c -> {
              ((ScanRun) c.getArgument(0)).setId(8L);
              return 1;
            });
    assertEquals(7L, service.start(req).getSourceReportRunId());
    verify(orchestrator).execute(8L, false);
  }
}
