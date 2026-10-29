package io.codesentry.project.service;

import io.codesentry.common.error.BusinessException;
import io.codesentry.git.GitRepositoryService;
import io.codesentry.project.dto.CreateProjectRequest;
import io.codesentry.project.mapper.ScanProjectMapper;
import io.codesentry.project.model.ProjectSourceType;
import io.codesentry.project.model.ScanProject;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScanProjectServiceTest {
    private final ScanProjectMapper mapper = mock(ScanProjectMapper.class);
    private final GitRepositoryService git = mock(GitRepositoryService.class);
    private final ScanProjectService service = new ScanProjectService(mapper, git);
    private ScanProject project(ProjectSourceType type) {
        ScanProject p = new ScanProject(); p.setId(1L); p.setSourceType(type); p.setEnabled(true);
        p.setCreatedAt(LocalDateTime.of(2026, 9, 3, 1, 0)); p.setLocalPath("/repo"); p.setProjectName("old");
        p.setDefaultBranch("master"); p.setRemoteUrl("git@example.com:group/repo.git");
        p.setManagedPath("/mirrors/existing.git"); when(mapper.findById(1L)).thenReturn(p); return p;
    }
    private CreateProjectRequest request(ProjectSourceType type) {
        CreateProjectRequest r = new CreateProjectRequest(); r.setProjectName(" 新名称 "); r.setSourceType(type);
        r.setLocalPath("/new-repo"); r.setDefaultBranch("master"); r.setRemoteUrl("git@example.com:group/repo.git"); return r;
    }
    @Test void editsLocalMetadataAndValidatesRepositoryWithoutChangingRegistrationTime() {
        ScanProject p = project(ProjectSourceType.LOCAL); LocalDateTime created = p.getCreatedAt();
        when(git.validateLocalRepository("/new-repo")).thenReturn(Path.of("/new-repo"));
        ScanProject updated = service.update(1L, request(ProjectSourceType.LOCAL));
        assertEquals("新名称", updated.getProjectName()); assertEquals("/new-repo", updated.getLocalPath());
        assertEquals(created, updated.getCreatedAt()); assertNotNull(updated.getUpdatedAt());
        verify(git).resolveCommit(updated, "master"); verify(mapper).update(updated);
        verify(git, never()).cloneMirror(any());
    }
    @Test void activeScanOrAiJobBlocksEditAndNoRepositorySideEffectsOccur() {
        project(ProjectSourceType.LOCAL); when(mapper.countActiveTasks(1L)).thenReturn(1);
        assertThrows(BusinessException.class, () -> service.update(1L, request(ProjectSourceType.LOCAL)));
        verifyNoInteractions(git); verify(mapper, never()).update(any());
    }
    @Test void sameRemoteDoesNotCloneButChangedRemoteUsesNewMirror() {
        project(ProjectSourceType.GIT); CreateProjectRequest r = request(ProjectSourceType.GIT);
        assertEquals("/mirrors/existing.git", service.update(1L, r).getManagedPath());
        verify(git, never()).cloneMirror(any());
        r.setRemoteUrl("git@example.com:group/new.git");
        when(git.cloneMirror(r.getRemoteUrl())).thenReturn(Path.of("/mirrors/new.git"));
        assertEquals("/mirrors/new.git", service.update(1L, r).getManagedPath());
        verify(git).cloneMirror(r.getRemoteUrl());
    }
    @Test void cannotSwitchSourceOrEditRemovedProject() {
        ScanProject p = project(ProjectSourceType.LOCAL);
        assertThrows(BusinessException.class, () -> service.update(1L, request(ProjectSourceType.GIT)));
        p.setEnabled(false);
        assertThrows(BusinessException.class, () -> service.update(1L, request(ProjectSourceType.LOCAL)));
        verifyNoInteractions(git); verify(mapper, never()).update(any());
    }
}
