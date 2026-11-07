package io.codesentry.scan.archive;

import io.codesentry.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class JarArchiveTest {
    @TempDir Path temp;
    private Path jar(String... names) throws Exception {
        Path path = temp.resolve("sample.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (String name : names) { zip.putNextEntry(new ZipEntry(name)); zip.write(new byte[]{1,2,3}); zip.closeEntry(); }
        }
        return path;
    }
    @Test void bootClassesAreAnalyzedSeparatelyFromDependenciesAndLoader() throws Exception {
        Path archive = jar("BOOT-INF/classes/com/example/App.class", "BOOT-INF/lib/dependency.jar", "org/springframework/boot/loader/Launcher.class");
        JarArchive.Layout layout = JarArchive.prepare(archive, temp.resolve("out"));
        assertEquals(temp.resolve("out/BOOT-INF/classes"), layout.getTarget());
        assertTrue(Files.exists(layout.getTarget().resolve("com/example/App.class")));
        assertEquals(1, layout.getDependencies().size());
        assertFalse(Files.exists(temp.resolve("out/org")));
    }
    @Test void ordinaryJarIsTheAnalysisTarget() throws Exception {
        Path archive = jar("com/example/App.class");
        assertEquals(archive, JarArchive.prepare(archive, temp.resolve("out")).getTarget());
    }
    @Test void traversalIsRejectedBeforeExtraction() throws Exception {
        Path archive = jar("../outside.class");
        assertThrows(BusinessException.class, () -> JarArchive.validate(archive));
        assertFalse(Files.exists(temp.getParent().resolve("outside.class")));
    }
    @Test void corruptAndClasslessArchivesAreRejected() throws Exception {
        Path archive = jar("readme.txt");
        assertThrows(BusinessException.class, () -> JarArchive.validate(archive));
        Files.writeString(archive, "not a zip");
        assertThrows(BusinessException.class, () -> JarArchive.validate(archive));
    }
}
