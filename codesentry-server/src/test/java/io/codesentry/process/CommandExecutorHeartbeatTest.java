package io.codesentry.process;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class CommandExecutorHeartbeatTest {
    @Test void silentLongCommandPublishesHeartbeatWithoutChangingCommandOutput() {
        AtomicBoolean heartbeat = new AtomicBoolean();
        CommandTraceHolder.listen(trace -> {
            if (trace.getOutput().contains("命令仍在执行")) heartbeat.set(true);
        });
        try {
            CommandResult result = new CommandExecutor().execute(null,
                    List.of("/bin/sh", "-c", "sleep 11"), Duration.ofSeconds(15));
            assertTrue(result.isSuccess()); assertTrue(heartbeat.get()); assertEquals("", result.getOutput());
        } finally { CommandTraceHolder.clear(); }
    }
}
