package io.codesentry.finding.service;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class RemediationHttpClientTest {
    @Test void baseClassesDetermineJavaRequirementAndOptionalMultiReleaseClassesDoNot() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try (ZipOutputStream zip=new ZipOutputStream(bytes)) {
            add(zip,"example/Base.class",52); add(zip,"META-INF/versions/17/example/Base.class",61);
            add(zip,"module-info.class",53);
        }
        assertEquals(8,RemediationHttpClient.classJava(bytes.toByteArray()));
    }
    @Test void malformedJarDoesNotBecomeAJava8CompatibilityClaim() throws Exception {
        assertNull(RemediationHttpClient.classJava(new byte[]{1,2,3}));
        assertThrows(IllegalArgumentException.class,()->RemediationHttpClient.artifactUrl("evil/../../:host","1.0"));
        assertThrows(IllegalArgumentException.class,()->RemediationHttpClient.artifactUrl("example:host","../1.0"));
    }
    private void add(ZipOutputStream zip,String name,int major) throws Exception {
        zip.putNextEntry(new ZipEntry(name)); zip.write(new byte[]{(byte)0xca,(byte)0xfe,(byte)0xba,(byte)0xbe,0,0,0,(byte)major}); zip.closeEntry();
    }
}
