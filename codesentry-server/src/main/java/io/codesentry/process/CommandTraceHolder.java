package io.codesentry.process;

import java.util.function.Consumer;

public final class CommandTraceHolder {

  private static final ThreadLocal<CommandTrace> LAST_TRACE = new ThreadLocal<>();
  private static final ThreadLocal<Consumer<CommandTrace>> LISTENER = new ThreadLocal<>();

  private CommandTraceHolder() {}

  public static void set(CommandTrace trace) {
    LAST_TRACE.set(trace);
  }

  public static CommandTrace get() {
    return LAST_TRACE.get();
  }

  public static void listen(Consumer<CommandTrace> listener) {
    LISTENER.set(listener);
  }

  public static Consumer<CommandTrace> listener() {
    return LISTENER.get();
  }

  public static void publish(CommandTrace trace) {
    Consumer<CommandTrace> listener = LISTENER.get();
    if (listener != null) {
      listener.accept(trace);
    }
  }

  public static void clear() {
    LAST_TRACE.remove();
    LISTENER.remove();
  }
}
