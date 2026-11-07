package io.codesentry.scan.service;

import io.codesentry.config.CodeSentryProperties;
import io.codesentry.finding.service.FindingPersistenceService;
import io.codesentry.git.*;
import io.codesentry.project.model.*;
import io.codesentry.project.service.ScanProjectService;
import io.codesentry.scan.engine.*;
import io.codesentry.scan.mapper.ScanRunMapper;
import io.codesentry.scan.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JarScanOrchestratorTest {
    @TempDir Path temp;
    private void scan(boolean spotbugsEnabled) throws Exception {
        Path archive = Files.writeString(temp.resolve("app.jar"), "sample bytes");
        ScanRun run = new ScanRun(); run.setId(1L); run.setProjectId(2L);
        run.setWorkspaceMode(WorkspaceMode.JAR_UPLOAD); run.setScanMode(ScanMode.FULL);
        ScanProject project = new ScanProject(); project.setId(2L); project.setSourceType(ProjectSourceType.JAR_UPLOAD);
        project.setLocalPath(temp.resolve("new-version.jar").toString()); project.setManagedPath(temp.toString());
        run.setArtifactPath(archive.toString());
        ScanRunMapper runs = mock(ScanRunMapper.class);
        when(runs.findById(1L)).thenReturn(run); when(runs.markStarted(any(), any(), any())).thenReturn(1);
        when(runs.updatePrepared(any())).thenReturn(1);
        ScanProjectService projects = mock(ScanProjectService.class); when(projects.requireById(2L)).thenReturn(project);
        GitRepositoryService git = mock(GitRepositoryService.class); when(git.repositoryPath(project)).thenReturn(temp);
        RepositorySnapshotService snapshots = mock(RepositorySnapshotService.class);
        ScanToolRunService tools = mock(ScanToolRunService.class); when(tools.start(any(), any(), any())).thenReturn(new ScanToolRun());
        ScannerAdapter trivy = mock(ScannerAdapter.class), bugs = mock(ScannerAdapter.class), source = mock(ScannerAdapter.class);
        when(trivy.name()).thenReturn("Trivy"); when(bugs.name()).thenReturn("SpotBugs-FindSecBugs"); when(source.name()).thenReturn("Semgrep");
        for (ScannerAdapter adapter : List.of(trivy, bugs, source)) {
            when(adapter.enabled()).thenReturn(true); when(adapter.available()).thenReturn(true);
            when(adapter.scan(any())).thenReturn(List.of()); when(adapter.reportExtension()).thenReturn("json");
        }
        when(bugs.enabled()).thenReturn(spotbugsEnabled);
        CodeSentryProperties properties = new CodeSentryProperties(); properties.getStorage().setRoot(temp.toString());
        new ScanOrchestrator(runs, projects, git, snapshots, mock(FindingFingerprintService.class),
                mock(FindingPersistenceService.class), tools, List.of(trivy, bugs, source), properties).execute(1L, false);
        assertEquals(JarUploadService.sha256(archive), run.getScanCommit());
        assertEquals(spotbugsEnabled ? ScanRunStatus.COMPLETED : ScanRunStatus.PARTIAL, run.getStatus());
        verify(trivy).scan(any()); verify(source, never()).scan(any());
        verify(git, never()).resolveCommit(any(), any()); verifyNoInteractions(snapshots);
        if (!spotbugsEnabled) verify(tools).skipped(eq(1L), eq("SpotBugs-FindSecBugs"), any());
    }
    @Test void uploadedJarScansOnlyTwoBinaryToolsWithoutGit() throws Exception { scan(true); }
    @Test void disabledRequiredToolCannotProduceCompletedCleanScan() throws Exception { scan(false); }
}
