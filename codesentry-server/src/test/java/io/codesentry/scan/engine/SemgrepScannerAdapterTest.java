package io.codesentry.scan.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.process.CommandExecutor;
import io.codesentry.process.CommandResult;
import io.codesentry.project.model.ScanProject;
import io.codesentry.rule.service.RuleProfileService;
import io.codesentry.scan.model.ScanMode;
import io.codesentry.scan.model.WorkspaceMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemgrepScannerAdapterTest {

    @TempDir
    Path temp;

    @Test
    void exclusionsAndStableRuleIdsArePassedToSemgrep() {
        CommandExecutor commands = mock(CommandExecutor.class);
        RuleProfileService profiles = mock(RuleProfileService.class);
        CodeSentryProperties properties = new CodeSentryProperties();
        ObjectMapper json = new ObjectMapper();
        when(profiles.semgrepConfigs("LOCAL_OWASP")).thenReturn(List.of("rules.yml"));
        when(commands.execute(any(), anyList(), any())).thenReturn(new CommandResult(0, ""));
        SemgrepScannerAdapter adapter = new SemgrepScannerAdapter(commands, json, properties, profiles) {
            @Override protected JsonNode readJson(Path report) {
                JsonNode root = json.createObjectNode();
                ((com.fasterxml.jackson.databind.node.ObjectNode) root).putArray("results");
                return root;
            }
        };
        ScanContext context = new ScanContext(1L, new ScanProject(), ScanMode.FULL,
                WorkspaceMode.BRANCH_SNAPSHOT, temp, temp, temp, "commit", null,
                "current", "LOCAL_OWASP");

        assertTrue(adapter.scan(context).isEmpty());

        verify(commands).execute(eq(temp), org.mockito.ArgumentMatchers.argThat(args ->
                args.contains("--no-rewrite-rule-ids") && args.contains("--exclude")
                        && args.contains("**/*.png")), any());
    }
}
