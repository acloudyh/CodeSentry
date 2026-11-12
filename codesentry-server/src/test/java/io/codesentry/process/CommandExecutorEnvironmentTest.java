package io.codesentry.process;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class CommandExecutorEnvironmentTest {
    @Test void environmentSecretIsNotInArgumentsAndStreamingOutputIsRedacted() {
        String secret="fixture-secret-never-log";List<CommandTrace> traces=new CopyOnWriteArrayList<>();CommandTraceHolder.listen(traces::add);
        try {
            CommandResult result=new CommandExecutor().execute(null,List.of("sh","-c","printf '%s\\n' \"$CODESENTRY_TEST_SECRET\""),Duration.ofSeconds(5),Map.of("CODESENTRY_TEST_SECRET",secret),s->s==null?null:s.replace(secret,"[REDACTED]"));
            assertEquals("[REDACTED]",result.getOutput());assertFalse(CommandTraceHolder.get().getCommandLine().contains(secret));assertFalse(CommandTraceHolder.get().getOutput().contains(secret));assertFalse(traces.isEmpty());assertTrue(traces.stream().noneMatch(t->t.getOutput().contains(secret)));
        } finally {CommandTraceHolder.clear();}
    }
}
