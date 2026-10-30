package io.codesentry.process;

import org.springframework.core.task.TaskExecutor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.CancellationException;

/** Tracks queued and running tasks; interruption also cancels blocking HTTP/process calls. */
public class CancellableTasks {
    private final ConcurrentHashMap<Long, FutureTask<Void>> tasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Thread> synchronous = new ConcurrentHashMap<>();

    public void registerCurrent(Long id) { synchronous.put(id, Thread.currentThread()); }
    public void unregisterCurrent(Long id) { synchronous.remove(id, Thread.currentThread()); }

    public void submit(Long id, TaskExecutor executor, Runnable action) {
        FutureTask<Void> task = new FutureTask<Void>(() -> { check(); action.run(); return null; }) {
            @Override protected void done() { tasks.remove(id, this); }
        };
        tasks.put(id, task);
        try { executor.execute(task); }
        catch (RuntimeException error) { tasks.remove(id, task); throw error; }
    }

    public void cancel(Long id) {
        FutureTask<Void> task = tasks.get(id);
        if (task != null) task.cancel(true);
        Thread thread = synchronous.get(id);
        if (thread != null) thread.interrupt();
    }

    public boolean isActive(Long id) {
        return tasks.containsKey(id) || synchronous.containsKey(id);
    }

    public static void check() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("任务已停止");
    }
}
