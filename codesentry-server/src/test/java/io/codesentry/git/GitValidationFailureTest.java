package io.codesentry.git;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.codesentry.config.CodeSentryProperties;
import io.codesentry.process.*;
import io.codesentry.project.dto.GitValidationResult;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class GitValidationFailureTest {
  @Test
  void reportsCertificateAuthAccessAndNetworkFailuresSeparately() {
    String[][] cases = {
      {"SSL certificate problem: self signed certificate", "CERTIFICATE_ERROR"},
      {"HTTP Basic: Access denied", "AUTHENTICATION_ERROR"},
      {"repository not found", "REPOSITORY_ACCESS_ERROR"},
      {"Could not resolve host", "CONNECTION_ERROR"},
      {"Host key verification failed", "SSH_HOST_KEY_ERROR"}
    };
    for (String[] test : cases) {
      CommandExecutor commands = mock(CommandExecutor.class);
      when(commands.execute(isNull(), anyList(), any(Duration.class)))
          .thenReturn(new CommandResult(128, test[0]));
      GitRepositoryService git = new GitRepositoryService(commands, new CodeSentryProperties());
      GitValidationResult result =
          git.validateConnection("https://example.invalid/repo.git", null, null, null);
      assertFalse(result.valid);
      assertEquals(test[1], result.code);
      assertTrue(result.branches.isEmpty());
      verify(commands)
          .execute(
              isNull(),
              argThat(args -> args.contains("ls-remote") && !args.contains("clone")),
              eq(Duration.ofSeconds(30)));
    }
  }
}
