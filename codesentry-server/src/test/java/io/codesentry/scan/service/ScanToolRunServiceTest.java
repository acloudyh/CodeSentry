package io.codesentry.scan.service;

import io.codesentry.process.CommandTrace;
import io.codesentry.scan.mapper.ScanToolRunMapper;
import io.codesentry.scan.model.ScanToolRun;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ScanToolRunServiceTest {

    @Test
    void commandMetadataIsTruncatedBeforeDatabasePersistence() {
        ScanToolRun run = new ScanToolRun();
        CommandTrace trace = new CommandTrace(List.of("scanner", "x".repeat(4000)),
                "/" + "w".repeat(2000), 0, "ok", LocalDateTime.now(), LocalDateTime.now());

        new ScanToolRunService(mock(ScanToolRunMapper.class)).updateRunningLog(run, trace);

        assertTrue(run.getCommandLine().length() <= 2000);
        assertTrue(run.getWorkingDirectory().length() <= 1000);
    }
}
