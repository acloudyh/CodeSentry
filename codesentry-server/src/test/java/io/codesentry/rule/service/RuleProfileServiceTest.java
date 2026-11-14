package io.codesentry.rule.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.codesentry.config.CodeSentryProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleProfileServiceTest {
  @TempDir Path temp;

  @Test
  void localAndComprehensiveProfilesMaterializeFrontendRulesButCommunityDoesNot() throws Exception {
    CodeSentryProperties properties = new CodeSentryProperties();
    properties.getScanners().getSemgrep().setConfig("auto");
    properties.getStorage().setRoot(temp.toString());
    CommunityRuleLibraryService library = mock(CommunityRuleLibraryService.class);
    Path snapshot = temp.resolve("community.yml");
    Files.writeString(snapshot, "rules: []");
    when(library.requireSnapshot()).thenReturn(snapshot);
    RuleProfileService service = new RuleProfileService(properties, library);
    List<String> local = service.semgrepConfigs(RuleProfileService.LOCAL_OWASP);
    assertEquals(2, local.size());
    assertTrue(
        Files.readString(Path.of(local.get(1))).contains("codesentry.frontend.vue-route-html"));
    assertTrue(Files.readString(Path.of(local.get(0))).contains("codesentry.jsp.request-to-file"));
    assertEquals(3, service.semgrepConfigs(RuleProfileService.COMPREHENSIVE).size());
    assertEquals(
        List.of(snapshot.toString()), service.semgrepConfigs(RuleProfileService.COMMUNITY_GENERAL));
    assertTrue(service.list().get(0).getRules().stream().anyMatch(x -> x.contains("React")));
    assertTrue(service.list().get(0).getRules().stream().anyMatch(x -> x.contains("Vue")));
  }

  @Test
  void missingCommunitySnapshotDoesNotFallBackToNetwork() {
    CodeSentryProperties properties = new CodeSentryProperties();
    properties.getStorage().setRoot(temp.toString());
    try (CommunityRuleLibraryService library =
        new CommunityRuleLibraryService(
            properties,
            mock(io.codesentry.process.CommandExecutor.class),
            new com.fasterxml.jackson.databind.ObjectMapper())) {
      RuleProfileService service = new RuleProfileService(properties, library);
      assertTrue(service.list().get(0).isOfflineReady());
      assertFalse(service.list().get(1).isOfflineReady());
      assertThrows(
          io.codesentry.common.error.BusinessException.class,
          () -> service.semgrepConfigs(RuleProfileService.COMMUNITY_GENERAL));
    }
  }
}
