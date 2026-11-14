package io.codesentry.process;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CancellableTasksTest {
  @Test
  void queuedTaskDoesNotStartAfterCancellation() {
    CancellableTasks tasks = new CancellableTasks();
    AtomicReference<Runnable> queued = new AtomicReference<>();
    AtomicBoolean executed = new AtomicBoolean();
    tasks.submit(1L, queued::set, () -> executed.set(true));
    tasks.cancel(1L);
    queued.get().run();
    assertFalse(executed.get());
  }

  @Test
  void runningTaskAndItsChildProcessAreTerminated() throws Exception {
    CancellableTasks tasks = new CancellableTasks();
    CommandExecutor executor = new CommandExecutor();
    CountDownLatch started = new CountDownLatch(1), exited = new CountDownLatch(1);
    AtomicLong childPid = new AtomicLong();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    tasks.submit(
        2L,
        runnable -> new Thread(runnable, "cancel-test").start(),
        () -> {
          CommandTraceHolder.listen(
              trace -> {
                String output = trace.getOutput().trim();
                if (!output.isEmpty()) {
                  childPid.set(Long.parseLong(output));
                  started.countDown();
                }
              });
          try {
            executor.execute(
                Paths.get(System.getProperty("java.io.tmpdir")),
                List.of("/bin/sh", "-c", "sleep 30 & echo $!; wait"),
                Duration.ofSeconds(40));
          } catch (java.util.concurrent.CancellationException expected) {
            // Interruption must stop the actual process, not only the database status.
          } catch (Throwable error) {
            failure.set(error);
          } finally {
            CommandTraceHolder.clear();
            exited.countDown();
          }
        });
    try {
      assertTrue(started.await(5, TimeUnit.SECONDS));
      tasks.cancel(2L);
      assertTrue(exited.await(5, TimeUnit.SECONDS));
      assertNull(failure.get());
      ProcessHandle child = ProcessHandle.of(childPid.get()).orElse(null);
      if (child != null) child.onExit().get(5, TimeUnit.SECONDS);
      assertFalse(ProcessHandle.of(childPid.get()).map(ProcessHandle::isAlive).orElse(false));
    } finally {
      tasks.cancel(2L);
    }
  }
}
