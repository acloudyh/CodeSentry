package io.codesentry.project.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.codesentry.common.error.BusinessException;
import io.codesentry.git.GitRepositoryService;
import io.codesentry.project.dto.CreateProjectRequest;
import io.codesentry.project.mapper.ScanProjectMapper;
import io.codesentry.project.model.ProjectSourceType;
import io.codesentry.project.model.ScanProject;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ScanProjectServiceTest {
  private final ScanProjectMapper mapper = mock(ScanProjectMapper.class);
  private final GitRepositoryService git = mock(GitRepositoryService.class);
  private final ScanProjectService service = new ScanProjectService(mapper, git);

  private ScanProject project(ProjectSourceType type) {
    ScanProject p = new ScanProject();
    p.setId(1L);
    p.setSourceType(type);
    p.setEnabled(true);
    p.setCreatedAt(LocalDateTime.of(2026, 9, 3, 1, 0));
    p.setLocalPath("/repo");
    p.setProjectName("old");
    p.setDefaultBranch("master");
    p.setRemoteUrl("git@example.com:group/repo.git");
    p.setManagedPath("/mirrors/existing.git");
    when(mapper.findById(1L)).thenReturn(p);
    when(mapper.findByIdForUpdate(1L)).thenReturn(p);
    return p;
  }

  private CreateProjectRequest request(ProjectSourceType type) {
    CreateProjectRequest r = new CreateProjectRequest();
    r.setProjectName(" 新名称 ");
    r.setSourceType(type);
    r.setLocalPath("/new-repo");
    r.setDefaultBranch("master");
    r.setRemoteUrl("git@example.com:group/repo.git");
    return r;
  }

  @Test
  void editsLocalMetadataAndValidatesRepositoryWithoutChangingRegistrationTime() {
    ScanProject p = project(ProjectSourceType.LOCAL);
    p.setTargetJavaVersion(11);
    LocalDateTime created = p.getCreatedAt();
    when(git.validateLocalRepository("/new-repo")).thenReturn(Path.of("/new-repo"));
    CreateProjectRequest request = request(ProjectSourceType.LOCAL);
    request.setTargetJavaVersion(8);
    ScanProject updated = service.update(1L, request);
    assertEquals(8, updated.getTargetJavaVersion());
    assertEquals("新名称", updated.getProjectName());
    assertEquals("/new-repo", updated.getLocalPath());
    assertEquals(created, updated.getCreatedAt());
    assertNotNull(updated.getUpdatedAt());
    verify(git).resolveCommit(updated, "master");
    verify(mapper).update(updated);
    verify(git, never()).cloneMirror(any());
  }

  @Test
  void activeScanOrAiJobBlocksEditAndNoRepositorySideEffectsOccur() {
    project(ProjectSourceType.LOCAL);
    when(mapper.countActiveTasks(1L)).thenReturn(1);
    assertThrows(
        BusinessException.class, () -> service.update(1L, request(ProjectSourceType.LOCAL)));
    verifyNoInteractions(git);
    verify(mapper, never()).update(any());
  }

  @Test
  void sameRemotePreservesCacheAndChangedRemoteQueuesWithoutNetwork() {
    project(ProjectSourceType.GIT);
    CreateProjectRequest r = request(ProjectSourceType.GIT);
    assertEquals("/mirrors/existing.git", service.update(1L, r).getManagedPath());
    verify(git, never()).cloneMirror(any());
    r.setRemoteUrl("git@example.com:group/new.git");
    ScanProject changed = service.update(1L, r);
    assertNull(changed.getManagedPath());
    assertEquals("PENDING", changed.getLastSyncStatus());
    assertEquals(1, changed.getRepositoryRevision());
    verify(git, never()).cloneMirror(anyString());
  }

  @Test
  void cannotSwitchSourceOrEditRemovedProject() {
    ScanProject p = project(ProjectSourceType.LOCAL);
    assertThrows(BusinessException.class, () -> service.update(1L, request(ProjectSourceType.GIT)));
    p.setEnabled(false);
    assertThrows(
        BusinessException.class, () -> service.update(1L, request(ProjectSourceType.LOCAL)));
    verifyNoInteractions(git);
    verify(mapper, never()).update(any());
  }

  @Test
  void javaVersionCanBeClearedWhenItIsNoLongerConfirmed() {
    ScanProject p = project(ProjectSourceType.LOCAL);
    p.setTargetJavaVersion(11);
    when(git.validateLocalRepository("/new-repo")).thenReturn(Path.of("/new-repo"));
    assertNull(service.update(1L, request(ProjectSourceType.LOCAL)).getTargetJavaVersion());
    verify(mapper).update(p);
  }

  @Test
  void frontendRuntimeClearsJavaVersionAndRecordsNodeVersion() {
    ScanProject p = project(ProjectSourceType.LOCAL);
    p.setTargetJavaVersion(8);
    when(git.validateLocalRepository("/new-repo")).thenReturn(Path.of("/new-repo"));
    CreateProjectRequest r = request(ProjectSourceType.LOCAL);
    r.setApplicationType(io.codesentry.project.model.ApplicationType.FRONTEND);
    r.setTargetNodeVersion("20.19.0");
    ScanProject saved = service.update(1L, r);
    assertNull(saved.getTargetJavaVersion());
    assertEquals("20.19.0", saved.getTargetNodeVersion());
    r.setApplicationType(io.codesentry.project.model.ApplicationType.BACKEND);
    r.setTargetJavaVersion(11);
    service.update(1L, r);
    assertNull(saved.getTargetNodeVersion());
    assertEquals(11, saved.getTargetJavaVersion());
  }

  @Test
  void syncQueuesAndCoalescesRepeatedRequestsWithoutNetwork() {
    ScanProject p = project(ProjectSourceType.GIT);
    assertSame(p, service.sync(1L));
    assertEquals("PENDING", p.getLastSyncStatus());
    long revision = p.getRepositoryRevision();
    service.sync(1L);
    assertEquals(revision, p.getRepositoryRevision());
    assertEquals("/mirrors/existing.git", p.getManagedPath());
    verify(git, never()).refresh(any());
    verify(git, never()).cloneMirror(anyString());
  }

  @Test
  void initialGitSavePersistsBeforeAnyClone() {
    ScanProject created = service.create(request(ProjectSourceType.GIT));
    assertEquals("PENDING", created.getLastSyncStatus());
    assertNull(created.getManagedPath());
    verify(mapper).insert(created);
    verify(git, never()).cloneMirror(anyString());
    verify(git, never()).cloneMirror(anyString(), any());
  }

  @Test
  void activeTasksAndLocalSourcesCannotBeSynchronized() {
    project(ProjectSourceType.LOCAL);
    assertThrows(BusinessException.class, () -> service.sync(1L));
    project(ProjectSourceType.GIT);
    when(mapper.countActiveTasks(1L)).thenReturn(1);
    assertThrows(BusinessException.class, () -> service.sync(1L));
    verify(git, never()).refresh(any());
  }

  @Test
  void credentialsArePreservedOnBlankEditAndExplicitlyClearable() {
    ScanProject p = project(ProjectSourceType.GIT);
    p.setRemoteUrl("https://example.invalid/repo.git");
    p.setGitUsername("user");
    p.setGitPasswordCiphertext("encrypted-fixture");
    CreateProjectRequest r = request(ProjectSourceType.GIT);
    r.setRemoteUrl(p.getRemoteUrl());
    r.setGitUsername("user");
    service.update(1L, r);
    assertEquals("encrypted-fixture", p.getGitPasswordCiphertext());
    r.setClearGitCredentials(true);
    service.update(1L, r);
    assertNull(p.getGitPasswordCiphertext());
    assertFalse(p.isGitCredentialConfigured());
  }

  @Test
  void changingCredentialAccountRequiresPasswordAndSshRejectsPasswordMode() {
    ScanProject p = project(ProjectSourceType.GIT);
    p.setRemoteUrl("https://example.invalid/repo.git");
    p.setGitUsername("old");
    p.setGitPasswordCiphertext("encrypted-fixture");
    CreateProjectRequest r = request(ProjectSourceType.GIT);
    r.setRemoteUrl(p.getRemoteUrl());
    r.setGitUsername("new");
    assertThrows(BusinessException.class, () -> service.update(1L, r));
    r.setRemoteUrl("git@example.invalid:repo.git");
    r.setGitPassword("fixture-only");
    assertThrows(BusinessException.class, () -> service.update(1L, r));
  }

  @Test
  void gitValidationDoesNotSaveCredentialsOrCreateAnAsset() {
    io.codesentry.project.dto.GitValidationRequest request =
        new io.codesentry.project.dto.GitValidationRequest();
    request.setRemoteUrl("https://example.invalid/repo.git");
    request.setGitUsername("user");
    request.setGitPassword("fixture-only-password");
    when(git.validateConnection(anyString(), any(), any(), any()))
        .thenReturn(
            new io.codesentry.project.dto.GitValidationResult(
                true, "SUCCESS", "ok", "main", java.util.List.of("main")));
    assertTrue(service.validateGit(request).valid);
    verifyNoInteractions(mapper);
    verify(git, never()).cloneMirror(anyString());
  }

  @Test
  void savedCredentialsCannotBeProbedAgainstAnotherUrlOrAccount() {
    ScanProject p = project(ProjectSourceType.GIT);
    p.setRemoteUrl("https://example.invalid/original.git");
    p.setGitUsername("saved-user");
    p.setGitPasswordCiphertext("encrypted-fixture");
    io.codesentry.project.dto.GitValidationRequest request =
        new io.codesentry.project.dto.GitValidationRequest();
    request.setRemoteUrl("https://other.invalid/repo.git");
    request.setUseStoredCredentials(true);
    request.setProjectId(1L);
    assertThrows(BusinessException.class, () -> service.validateGit(request));
    verify(git, never()).validateConnection(any(), any(), any(), any());
    request.setRemoteUrl(p.getRemoteUrl());
    request.setGitUsername("another-user");
    assertThrows(BusinessException.class, () -> service.validateGit(request));
    request.setGitUsername("saved-user");
    when(git.validateConnection(p.getRemoteUrl(), "saved-user", null, p))
        .thenReturn(
            new io.codesentry.project.dto.GitValidationResult(
                true, "SUCCESS", "ok", null, java.util.List.of()));
    assertTrue(service.validateGit(request).valid);
  }
}
