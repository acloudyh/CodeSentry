package io.codesentry.git;

import io.codesentry.config.CodeSentryProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GitTlsTrustTest {
    @TempDir Path temp;
    @Test void customTrustIsScopedToHttpsHostAndPortAndDoesNotDisableVerification() throws Exception {
        CodeSentryProperties props=new CodeSentryProperties();props.getStorage().setRoot(temp.toString());GitTlsTrust trust=new GitTlsTrust(props);
        String origin="https://git.example.invalid:8443";StringBuilder key=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(origin.getBytes(StandardCharsets.UTF_8)))key.append(String.format("%02x",b));
        Files.createDirectories(temp.resolve("git-trust"));Path ca=Files.writeString(temp.resolve("git-trust").resolve(key+".pem"),"public certificate fixture");
        List<String> original=List.of("git","ls-remote",origin+"/group/repo.git");List<String> command=trust.configure(original,origin+"/group/repo.git");
        assertTrue(command.contains("http."+origin+"/.sslCAInfo="+ca));assertTrue(command.contains("http."+origin+"/.sslVerify=true"));assertFalse(command.stream().anyMatch(s->s.contains("sslVerify=false")));
        assertEquals(original,trust.configure(original,"https://git.example.invalid:8444/group/repo.git"));assertEquals(original,trust.configure(original,"https://other.example.invalid:8443/group/repo.git"));assertEquals(original,trust.configure(original,"http://git.example.invalid:8443/group/repo.git"));
    }
}
