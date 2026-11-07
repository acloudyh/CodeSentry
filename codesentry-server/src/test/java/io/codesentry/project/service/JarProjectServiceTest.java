package io.codesentry.project.service;

import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.project.mapper.ScanProjectMapper;
import io.codesentry.project.model.*;
import io.codesentry.scan.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.io.ByteArrayOutputStream;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JarProjectServiceTest {
    @TempDir Path temp;
    private final ScanProjectMapper projects = mock(ScanProjectMapper.class);
    private final ScanProject project = new ScanProject();
    private JarProjectService service() throws Exception {
        CodeSentryProperties props = new CodeSentryProperties(); props.getStorage().setRoot(temp.toString());
        project.setId(1L); project.setSourceType(ProjectSourceType.JAR_UPLOAD); project.setEnabled(true);
        Path old = Files.createDirectories(temp.resolve("old")).resolve("original.jar"); Files.writeString(old, "original bytes");
        project.setLocalPath(old.toString()); project.setManagedPath(old.getParent().toString()); project.setProjectName("original");
        when(projects.findByIdForUpdate(1L)).thenReturn(project); when(projects.update(any())).thenReturn(1);
        return new JarProjectService(projects, new JarUploadService(props, projects, mock(ScanRunService.class)));
    }
    private MockMultipartFile replacement() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) { zip.putNextEntry(new ZipEntry("example/New.class")); zip.write(new byte[]{1,2}); zip.closeEntry(); }
        return new MockMultipartFile("file", "new.jar", "application/java-archive", out.toByteArray());
    }
    @Test void metadataOnlyEditKeepsOriginalArchive() throws Exception {
        JarProjectService service = service(); String original = project.getLocalPath();
        ScanProject result = service.update(1L, " 修改名称 ", 11, null);
        assertEquals("修改名称", result.getProjectName()); assertEquals(11, result.getTargetJavaVersion());
        assertEquals(original, result.getLocalPath()); assertEquals("original bytes", Files.readString(Path.of(original)));
    }
    @Test void replacementKeepsOldVersionAndProjectId() throws Exception {
        JarProjectService service = service(); Path original = Path.of(project.getLocalPath());
        ScanProject result = service.update(1L, "new name", 8, replacement());
        assertEquals(1L, result.getId()); assertNotEquals(original.toString(), result.getLocalPath());
        assertTrue(Files.exists(Path.of(result.getLocalPath()))); assertEquals("original bytes", Files.readString(original));
    }
    @Test void invalidReplacementDoesNotChangeProject() throws Exception {
        JarProjectService service = service(); String original = project.getLocalPath();
        assertThrows(BusinessException.class, () -> service.update(1L, "new", null,
                new MockMultipartFile("file", "broken.jar", "application/octet-stream", new byte[]{1,2})));
        assertEquals(original, project.getLocalPath()); assertEquals("original", project.getProjectName());
        verify(projects, never()).update(any());
    }
    @Test void activeTasksAndRepositorySourcesAreRejected() throws Exception {
        JarProjectService service = service(); when(projects.countActiveTasks(1L)).thenReturn(1);
        assertThrows(BusinessException.class, () -> service.update(1L, "new", null, replacement()));
        when(projects.countActiveTasks(1L)).thenReturn(0); project.setSourceType(ProjectSourceType.LOCAL);
        assertThrows(BusinessException.class, () -> service.update(1L, "new", null, null));
        verify(projects, never()).update(any());
    }
    @Test void databaseFailureDeletesOnlyUncommittedNewArchive() throws Exception {
        JarProjectService service = service(); Path original = Path.of(project.getLocalPath());
        when(projects.update(any())).thenThrow(new org.springframework.dao.DuplicateKeyException("name exists"));
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> service.update(1L, "conflict", null, replacement()));
        assertTrue(Files.exists(original));
        try (var files = Files.list(temp.resolve("uploads"))) { assertEquals(0, files.count()); }
    }
}
