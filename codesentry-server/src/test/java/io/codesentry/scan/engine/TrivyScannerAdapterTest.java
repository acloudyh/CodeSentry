package io.codesentry.scan.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.process.CommandExecutor;
import io.codesentry.process.CommandResult;
import io.codesentry.project.model.ScanProject;
import io.codesentry.scan.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrivyScannerAdapterTest {
    @TempDir Path temp;
    private final CommandExecutor commands = mock(CommandExecutor.class);
    private final CodeSentryProperties properties = new CodeSentryProperties();
    private TrivyScannerAdapter adapter() {
        ObjectMapper json = new ObjectMapper();
        return new TrivyScannerAdapter(commands, json, properties) {
            @Override protected JsonNode readJson(Path path) { return json.createObjectNode(); }
        };
    }
    private ScanContext context() {
        return new ScanContext(1L, new ScanProject(), ScanMode.FULL, WorkspaceMode.BRANCH_SNAPSHOT,
                temp, temp, temp, "commit", null, "current", "GENERAL");
    }
    @Test void offlineDefaultAndExplicitTimeoutArePassedToCliWithOuterGrace() {
        when(commands.execute(any(), anyList(), any())).thenReturn(new CommandResult(0, ""));
        assertTrue(adapter().scan(context()).isEmpty());
        verify(commands).execute(eq(temp), argThat(args -> args.contains("--offline-scan")
                && args.contains("rootfs")
                && args.contains("--skip-db-update") && args.contains("--skip-java-db-update")
                && args.contains("--timeout") && args.contains("180s") && args.contains("vuln,secret")
                && args.contains("--skip-files") && args.contains("**/*.png")), eq(Duration.ofSeconds(195)));
    }
    @Test void configurableTimeoutAndOnlineModeAreHonored() {
        properties.getScanners().getTrivy().setTimeoutSeconds(60);
        properties.getScanners().getTrivy().setOfflineScan(false);
        when(commands.execute(any(), anyList(), any())).thenReturn(new CommandResult(0, ""));
        adapter().scan(context());
        verify(commands).execute(eq(temp), argThat(args -> !args.contains("--offline-scan") && args.contains("60s")), eq(Duration.ofSeconds(75)));
    }
    @Test void trivyDeadlineHasActionableFailureInsteadOfGenericError() {
        when(commands.execute(any(), anyList(), any())).thenReturn(new CommandResult(1, "semaphore acquire: context deadline exceeded"));
        BusinessException error = assertThrows(BusinessException.class, () -> adapter().scan(context()));
        assertTrue(error.getMessage().contains("180 秒"));
    }
    @Test void renamedJarFilesAreCopiedToAStandardJarAlias() throws Exception {
        Path legacy = temp.resolve("legacy.jar37");
        Files.writeString(legacy, "archive");

        Map<String, String> aliases = adapter().prepareArchiveAliases(context());

        assertEquals(1, aliases.size());
        String alias = aliases.keySet().iterator().next();
        assertTrue(alias.endsWith(".jar"));
        assertEquals("legacy.jar37", aliases.get(alias));
        assertTrue(Files.isRegularFile(temp.resolve("trivy-archive-aliases-current").resolve(alias)));
    }
}
