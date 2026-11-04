package io.codesentry.rule.service;

import io.codesentry.config.CodeSentryProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuleProfileServiceTest {
    @TempDir Path temp;

    @Test
    void localAndComprehensiveProfilesMaterializeFrontendRulesButCommunityDoesNot() throws Exception {
        CodeSentryProperties properties = new CodeSentryProperties();
        properties.getScanners().getSemgrep().setConfig("auto");
        properties.getStorage().setRoot(temp.toString());
        RuleProfileService service = new RuleProfileService(properties);
        List<String> local = service.semgrepConfigs(RuleProfileService.LOCAL_OWASP);
        assertEquals(2, local.size());
        assertTrue(Files.readString(Path.of(local.get(1)))
                .contains("codesentry.frontend.vue-route-html"));
        assertTrue(Files.readString(Path.of(local.get(0)))
                .contains("codesentry.jsp.request-to-file"));
        assertEquals(3, service.semgrepConfigs(RuleProfileService.COMPREHENSIVE).size());
        assertEquals(List.of(properties.getScanners().getSemgrep().getConfig()),
                service.semgrepConfigs(RuleProfileService.COMMUNITY_GENERAL));
        assertTrue(service.list().get(0).getRules().stream().anyMatch(x -> x.contains("React")));
        assertTrue(service.list().get(0).getRules().stream().anyMatch(x -> x.contains("Vue")));
    }
}
