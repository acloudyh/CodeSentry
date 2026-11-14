package io.codesentry.git;

import io.codesentry.config.CodeSentryProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Optional CA trust is restricted to one HTTPS origin, including its port. TLS verification stays
 * enabled.
 */
@Component
public class GitTlsTrust {
  private final Path directory;

  public GitTlsTrust(CodeSentryProperties properties) {
    directory = properties.getStorage().rootPath().resolve("git-trust");
  }

  public List<String> configure(List<String> original, String remoteUrl) {
    if (remoteUrl == null) return original;
    try {
      URI uri = URI.create(remoteUrl);
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return original;
      int port = uri.getPort() < 0 ? 443 : uri.getPort();
      String origin = "https://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(origin.getBytes(StandardCharsets.UTF_8));
      StringBuilder key = new StringBuilder();
      for (byte b : hash) key.append(String.format("%02x", b));
      Path certificate = directory.resolve(key + ".pem");
      if (!Files.isRegularFile(certificate)) return original;
      List<String> command = new ArrayList<>();
      command.add(original.get(0));
      command.addAll(
          List.of(
              "-c",
              "http." + origin + "/.sslCAInfo=" + certificate,
              "-c",
              "http." + origin + "/.sslVerify=true"));
      command.addAll(original.subList(1, original.size()));
      return command;
    } catch (Exception ignored) {
      return original;
    }
  }
}
