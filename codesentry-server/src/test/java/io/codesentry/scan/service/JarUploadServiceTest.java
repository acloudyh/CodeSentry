package io.codesentry.scan.service;

import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.project.mapper.ScanProjectMapper;
import io.codesentry.project.model.*;
import io.codesentry.scan.dto.CreateScanRunRequest;
import io.codesentry.scan.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class JarUploadServiceTest {
    @TempDir Path temp;
    private final ScanProjectMapper projects = mock(ScanProjectMapper.class);
    private final ScanRunService scans = mock(ScanRunService.class);
    private JarUploadService service() {
        CodeSentryProperties props = new CodeSentryProperties(); props.getStorage().setRoot(temp.toString());
        when(projects.insert(any())).thenAnswer(call -> { ((ScanProject)call.getArgument(0)).setId(9L); return 1; });
        return new JarUploadService(props, projects, scans);
    }
    private MockMultipartFile file(String name) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) { zip.putNextEntry(new ZipEntry("com/example/App.class")); zip.write(new byte[]{1,2,3}); zip.closeEntry(); }
        return new MockMultipartFile("file", name, "application/java-archive", bytes.toByteArray());
    }
    @Test void uploadPersistsIndependentSnapshotAndEnqueuesBothToolScan() throws Exception {
        JarUploadService service = service();
        ScanRun result = new ScanRun(); result.setId(12L); when(scans.start(any())).thenReturn(result);
        assertEquals(12L, service.upload(file("app.jar"), "示例", 11).getId());
        ArgumentCaptor<ScanProject> project = ArgumentCaptor.forClass(ScanProject.class); verify(projects).insert(project.capture());
        assertEquals(ProjectSourceType.JAR_UPLOAD, project.getValue().getSourceType());
        Path archive = Path.of(project.getValue().getLocalPath());
        assertTrue(Files.exists(archive));
        assertEquals(JarUploadService.sha256(archive), Files.readString(archive.getParent().resolve(".sha256")));
        ArgumentCaptor<CreateScanRunRequest> request = ArgumentCaptor.forClass(CreateScanRunRequest.class); verify(scans).start(request.capture());
        assertEquals(ScanMode.FULL, request.getValue().getScanMode());
        assertEquals(WorkspaceMode.JAR_UPLOAD, request.getValue().getWorkspaceMode());
        assertFalse(request.getValue().isRefreshRemote());
    }
    @Test void rejectsTraversalAndRenamedNonArchiveBeforeCreatingProject() throws Exception {
        JarUploadService service = service();
        assertThrows(BusinessException.class, () -> service.upload(file("../bad.jar"), null, null));
        assertThrows(BusinessException.class, () -> service.upload(new MockMultipartFile("file", "bad.jar", "application/octet-stream", "bad".getBytes()), null, null));
        verify(projects, never()).insert(any());
        try (var files = Files.list(temp.resolve("uploads"))) { assertEquals(0, files.count()); }
    }
    @Test void failedQueueSubmissionCleansUploadedFilesAndDisablesProject() throws Exception {
        JarUploadService service = service(); when(scans.start(any())).thenThrow(new IllegalStateException("full"));
        assertThrows(IllegalStateException.class, () -> service.upload(file("app.jar"), null, null));
        verify(projects).disableById(9L);
        try (var files = Files.list(temp.resolve("uploads"))) { assertEquals(0, files.count()); }
    }
    @Test void acceptsTwoGiBBoundaryAndRejectsOneByteOverWithoutReadingContent() throws Exception {
        JarUploadService service = service();
        MockMultipartFile original = file("boundary.jar");
        MockMultipartFile boundary = new MockMultipartFile("file", "boundary.jar", "application/java-archive", original.getBytes()) {
            @Override public long getSize() { return 2L * 1024 * 1024 * 1024; }
        };
        assertEquals(2147483648L, JarUploadService.MAX_UPLOAD_BYTES);
        assertTrue(Files.exists(service.store(boundary).getArchive()));
        org.springframework.web.multipart.MultipartFile tooBig = mock(org.springframework.web.multipart.MultipartFile.class);
        when(tooBig.getOriginalFilename()).thenReturn("over.jar");
        when(tooBig.getSize()).thenReturn(2147483649L);
        assertThrows(BusinessException.class, () -> service.store(tooBig));
        verify(tooBig, never()).getInputStream();
    }
    @Test void registeringJarOnlyStoresProjectWithoutStartingScan() throws Exception {
        ScanProject project = service().register(file("registered.jar"), "JAR 项目", 17);
        assertEquals(9L, project.getId());
        assertEquals(ProjectSourceType.JAR_UPLOAD, project.getSourceType());
        assertEquals(17, project.getTargetJavaVersion());
        assertTrue(Files.isRegularFile(Path.of(project.getLocalPath())));
        verifyNoInteractions(scans);
    }
    @Test void registrationFailureCleansStoredFileWithoutSubmittingScan() throws Exception {
        JarUploadService service = service();
        doThrow(new IllegalStateException("database unavailable")).when(projects).insert(any());
        assertThrows(IllegalStateException.class, () -> service.register(file("registered.jar"), "JAR", 17));
        verifyNoInteractions(scans);
        try (var files = Files.list(temp.resolve("uploads"))) { assertEquals(0, files.count()); }
    }
}
