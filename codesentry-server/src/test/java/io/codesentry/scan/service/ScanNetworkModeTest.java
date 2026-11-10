package io.codesentry.scan.service;

import io.codesentry.project.model.*;
import io.codesentry.project.service.ScanProjectService;
import io.codesentry.rule.service.RuleProfileService;
import io.codesentry.scan.dto.CreateScanRunRequest;
import io.codesentry.scan.mapper.ScanRunMapper;
import io.codesentry.scan.model.*;
import io.codesentry.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScanNetworkModeTest {
    final ScanRunMapper mapper=mock(ScanRunMapper.class);
    final ScanProjectService projects=mock(ScanProjectService.class);
    final ScanOrchestrator worker=mock(ScanOrchestrator.class);
    final RuleProfileService rules=mock(RuleProfileService.class);
    final ScanRunService service=new ScanRunService(mapper,projects,worker,mock(ScanToolRunService.class),rules,Runnable::run);
    CreateScanRunRequest request(NetworkMode mode) {
        ScanProject p=new ScanProject();p.setId(1L);p.setSourceType(ProjectSourceType.LOCAL);p.setTargetJavaVersion(8);
        when(projects.requireById(1L)).thenReturn(p);
        when(mapper.insert(any())).thenAnswer(call->{((ScanRun)call.getArgument(0)).setId(9L);return 1;});
        CreateScanRunRequest r=new CreateScanRunRequest();r.setProjectId(1L);r.setScanMode(ScanMode.FULL);
        r.setWorkspaceMode(WorkspaceMode.CURRENT);r.setNetworkMode(mode);r.setRuleProfile("LOCAL_OWASP");return r;
    }
    @Test void localModeIsPersistedAndDoesNotRefreshRemoteRepositories() {
        ScanRun run=service.start(request(NetworkMode.LOCAL));
        assertEquals(NetworkMode.LOCAL,run.getNetworkMode());assertEquals("NOT_REQUESTED",run.getRemediationStatus());
        verify(worker).execute(9L,false);
    }
    @Test void onlineModeIsPersistedBeforeWorkerStarts() {
        ScanRun run=service.start(request(NetworkMode.ONLINE));
        assertEquals(NetworkMode.ONLINE,run.getNetworkMode());assertEquals("PENDING",run.getRemediationStatus());
        verify(worker).execute(9L,true);
        assertEquals(8,projects.requireById(1L).getTargetJavaVersion());
    }
    @Test void localModeRejectsUncachedRulesRatherThanFetchingThemSilently() {
        CreateScanRunRequest r=request(NetworkMode.LOCAL);when(rules.semgrepConfigs(any())).thenReturn(List.of("p/default"));
        assertThrows(BusinessException.class,()->service.start(r));verify(mapper,never()).insert(any());verifyNoInteractions(worker);
    }
}
