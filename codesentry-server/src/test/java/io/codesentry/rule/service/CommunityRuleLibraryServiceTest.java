package io.codesentry.rule.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.process.CommandExecutor;
import io.codesentry.process.CommandResult;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommunityRuleLibraryServiceTest {
  @TempDir Path temp;

  private CommunityRuleLibraryService service(CommandExecutor executor) {
    CodeSentryProperties properties = new CodeSentryProperties();
    properties.getStorage().setRoot(temp.toString());
    return new CommunityRuleLibraryService(properties, executor, new ObjectMapper());
  }

  private void download(CommandExecutor executor, String yaml, boolean valid) {
    doAnswer(
            call -> {
              List<String> args = call.getArgument(1);
              Path output = Path.of(args.get(args.indexOf("--output") + 1));
              Files.writeString(output, args.get(0).equals("curl") ? yaml : "{\"errors\":[]}");
              return new CommandResult(args.get(0).equals("curl") || valid ? 0 : 2, "");
            })
        .when(executor)
        .execute(any(), anyList(), any());
  }

  @Test
  void successfulUpdateSelectsImmutableLocalSnapshotAndKeepsPreviousFile() throws Exception {
    CommandExecutor commands = mock(CommandExecutor.class);
    try (CommunityRuleLibraryService library = service(commands)) {
      download(commands, "rules:\n- id: first\n", true);
      library.updateSnapshot();
      assertEquals("SUCCEEDED", library.status().getState());
      assertEquals(1, library.status().getRuleCount());
      Path first = library.requireSnapshot();
      download(commands, "rules:\n- id: second\n- id: third\n", true);
      library.updateSnapshot();
      assertNotEquals(first, library.requireSnapshot());
      assertTrue(Files.readString(first).contains("first"));
      assertEquals(2, library.status().getRuleCount());
    }
  }

  @Test
  void invalidOrIncompatibleDownloadPreservesPreviousSnapshot() {
    CommandExecutor commands = mock(CommandExecutor.class);
    try (CommunityRuleLibraryService library = service(commands)) {
      download(commands, "rules:\n- id: first\n", true);
      library.updateSnapshot();
      Path first = library.requireSnapshot();
      download(commands, "rules:\n- id: incompatible\n", false);
      library.updateSnapshot();
      assertEquals("FAILED", library.status().getState());
      assertEquals(first, library.requireSnapshot());
      download(commands, "<html>proxy error</html>", true);
      library.updateSnapshot();
      assertEquals("FAILED", library.status().getState());
      assertEquals(first, library.requireSnapshot());
      doReturn(new CommandResult(7, "offline")).when(commands).execute(any(), anyList(), any());
      library.updateSnapshot();
      assertEquals(first, library.requireSnapshot());
      assertTrue(library.status().isAvailable());
    }
  }

  @Test
  void concurrentRequestsShareOneUpdate() throws Exception {
    CommandExecutor commands = mock(CommandExecutor.class);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    when(commands.execute(any(), anyList(), any()))
        .thenAnswer(
            call -> {
              entered.countDown();
              release.await(5, TimeUnit.SECONDS);
              return new CommandResult(7, "offline");
            });
    try (CommunityRuleLibraryService library = service(commands)) {
      library.requestUpdate();
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertEquals("RUNNING", library.requestUpdate().getState());
      verify(commands, times(1)).execute(any(), anyList(), any());
      release.countDown();
    } finally {
      release.countDown();
    }
  }
}
