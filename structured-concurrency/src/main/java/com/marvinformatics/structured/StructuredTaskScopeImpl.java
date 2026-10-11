package com.marvinformatics.structured;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

/**
 * The only {@link StructuredTaskScope} implementation. Each scope tracks its subtask threads
 * under one lock, and a per-thread stack of open scopes detects structure violations.
 */
final class StructuredTaskScopeImpl<T, R, R_X extends Throwable>
    implements StructuredTaskScope<T, R, R_X> {

  private static final ThreadLocal<Deque<StructuredTaskScopeImpl<?, ?, ?>>> OPEN_SCOPES =
      ThreadLocal.withInitial(ArrayDeque::new);
  private static final ThreadLocal<StructuredTaskScopeImpl<?, ?, ?>> SCOPE_RUNNING_ON_COMPLETE =
      new ThreadLocal<>();

  private enum Phase {
    FORKING,
    JOINING,
    JOINED,
  }

  private final Joiner<T, R, R_X> joiner;
  private final ConfigImpl config;
  private final Thread owner = Thread.currentThread();

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition stateChanged = lock.newCondition();
  private final Set<Thread> threads = new HashSet<>();
  private Future<?> scheduledTimeout;

  private volatile Phase phase = Phase.FORKING;
  private volatile boolean cancelled;
  private boolean timedOut;
  private boolean closed;
  private boolean forked;
  private boolean removedFromOpenScopes;

  @SuppressWarnings("unchecked")
  private StructuredTaskScopeImpl(Joiner<? super T, ? extends R, R_X> joiner, ConfigImpl config) {
    this.joiner = (Joiner<T, R, R_X>) joiner;
    this.config = config;
  }

  static <T, R, R_X extends Throwable> StructuredTaskScope<T, R, R_X> open(
      Joiner<? super T, ? extends R, R_X> joiner, UnaryOperator<Configuration> configOperator) {
    Objects.requireNonNull(joiner, "joiner");
    Objects.requireNonNull(configOperator, "configOperator");
    var config = (ConfigImpl) Objects.requireNonNull(configOperator.apply(ConfigImpl.DEFAULT));
    var scope = new StructuredTaskScopeImpl<T, R, R_X>(joiner, config);
    OPEN_SCOPES.get().push(scope);
    scope.scheduleTimeoutOrExpireNow();
    return scope;
  }

  private void scheduleTimeoutOrExpireNow() {
    Duration timeout = config.timeout();
    if (timeout == null) {
      return;
    }
    if (timeout.isPositive()) {
      scheduledTimeout = TimeoutScheduler.INSTANCE.schedule(
          this::cancelDueToTimeout, timeout.toNanos(), TimeUnit.NANOSECONDS);
    } else {
      cancelDueToTimeout();
    }
  }

  private void cancelDueToTimeout() {
    lock.lock();
    try {
      if (phase != Phase.JOINED && !closed) {
        timedOut = true;
        cancel();
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public <U extends T> Subtask<U> fork(Callable<? extends U> task) {
    Objects.requireNonNull(task, "task");
    requireCurrentThreadIsOwner();
    if (closed) {
      throw new IllegalStateException("Scope is closed");
    }
    if (phase != Phase.FORKING) {
      throw new IllegalStateException("Already joined or join attempted");
    }

    var subtask = new SubtaskImpl<U>(this, task);
    if (joiner.onFork(forJoiner(subtask))) {
      cancel();
    }
    if (cancelled) {
      forked = true;
      return subtask;
    }

    Thread thread = config.threadFactory().newThread(subtask::callTaskAndReportToScope);
    if (thread == null) {
      throw new RejectedExecutionException("Thread factory did not create a thread");
    }
    forked = true;
    startUnlessCancelled(thread);
    return subtask;
  }

  private void startUnlessCancelled(Thread thread) {
    lock.lock();
    try {
      if (cancelled) {
        return;
      }
      threads.add(thread);
      try {
        thread.start();
      } catch (Throwable e) {
        threads.remove(thread);
        throw e;
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public <U extends T> Subtask<U> fork(Runnable task) {
    Objects.requireNonNull(task, "task");
    return fork(() -> {
      task.run();
      return null;
    });
  }

  @Override
  public R join() throws R_X, InterruptedException {
    requireCurrentThreadIsOwner();
    lock.lock();
    try {
      if (closed) {
        throw new IllegalStateException("Scope is closed");
      }
      if (phase == Phase.JOINED) {
        throw new IllegalStateException("Already joined");
      }
      phase = Phase.JOINING;
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      while (!threads.isEmpty() && !cancelled) {
        stateChanged.await();
      }
      phase = Phase.JOINED;
      cancelScheduledTimeout();
    } finally {
      lock.unlock();
    }
    return timedOut ? joiner.timeout() : joiner.result();
  }

  @Override
  public boolean isCancelled() {
    return cancelled;
  }

  @Override
  public void close() {
    requireCurrentThreadIsOwner();
    if (closed) {
      return;
    }

    boolean innerScopesWereOpen = false;
    if (!removedFromOpenScopes) {
      Deque<StructuredTaskScopeImpl<?, ?, ?>> openScopes = OPEN_SCOPES.get();
      while (openScopes.peek() != this) {
        innerScopesWereOpen = true;
        popAndShutDownInnermost(openScopes);
      }
      openScopes.pop();
      removedFromOpenScopes = true;
      if (openScopes.isEmpty()) {
        OPEN_SCOPES.remove();
      }
    }

    cancelAndAwaitForkedThreads();
    lock.lock();
    try {
      closed = true;
    } finally {
      lock.unlock();
    }

    if (innerScopesWereOpen) {
      throw new StructureViolationException("Scope closed before nested scopes");
    }
    if (forked && phase == Phase.FORKING) {
      throw new IllegalStateException("Owner did not join after forking");
    }
  }

  private static void popAndShutDownInnermost(Deque<StructuredTaskScopeImpl<?, ?, ?>> openScopes) {
    var innermost = openScopes.pop();
    innermost.removedFromOpenScopes = true;
    innermost.cancelAndAwaitForkedThreads();
  }

  private void cancelAndAwaitForkedThreads() {
    cancel();
    lock.lock();
    try {
      while (!threads.isEmpty()) {
        stateChanged.awaitUninterruptibly();
      }
      cancelScheduledTimeout();
    } finally {
      lock.unlock();
    }
  }

  private void cancel() {
    lock.lock();
    try {
      if (!cancelled) {
        cancelled = true;
        threads.forEach(Thread::interrupt);
        stateChanged.signalAll();
      }
    } finally {
      lock.unlock();
    }
  }

  private void cancelScheduledTimeout() {
    if (scheduledTimeout != null) {
      scheduledTimeout.cancel(false);
      scheduledTimeout = null;
    }
  }

  private void requireCurrentThreadIsOwner() {
    if (Thread.currentThread() != owner) {
      throw new WrongThreadException("Current thread not owner");
    }
  }

  @SuppressWarnings("unchecked")
  private Subtask<T> forJoiner(SubtaskImpl<?> subtask) {
    return (Subtask<T>) subtask;
  }

  private void publishOutcomeAndNotifyJoiner(SubtaskImpl<?> subtask, Subtask.State outcome) {
    if (cancelled) {
      return;
    }
    subtask.state = outcome;
    SCOPE_RUNNING_ON_COMPLETE.set(this);
    try {
      if (joiner.onComplete(forJoiner(subtask))) {
        cancel();
      }
    } finally {
      SCOPE_RUNNING_ON_COMPLETE.remove();
    }
  }

  private void removeThreadAndSignalIfNoneLeft(Thread finishedThread) {
    lock.lock();
    try {
      threads.remove(finishedThread);
      if (threads.isEmpty()) {
        stateChanged.signalAll();
      }
    } finally {
      lock.unlock();
    }
  }

  private boolean canReadSubtaskOutcome() {
    return phase == Phase.JOINED || SCOPE_RUNNING_ON_COMPLETE.get() == this;
  }

  private static void cancelScopesLeftOpenByCurrentThread() {
    Deque<StructuredTaskScopeImpl<?, ?, ?>> openScopes = OPEN_SCOPES.get();
    while (!openScopes.isEmpty()) {
      popAndShutDownInnermost(openScopes);
    }
    OPEN_SCOPES.remove();
  }

  @Override
  public String toString() {
    String stateDescription = closed ? "closed" : cancelled ? "cancelled" : "open";
    String namePrefix = config.name() != null ? config.name() + ", " : "";
    return "StructuredTaskScope[" + namePrefix + stateDescription + "]";
  }

  static final class SubtaskImpl<T> implements Subtask<T> {
    private final StructuredTaskScopeImpl<?, ?, ?> scope;
    private final Callable<? extends T> task;
    private volatile State state = State.UNAVAILABLE;
    private T result;
    private Throwable failure;

    private SubtaskImpl(StructuredTaskScopeImpl<?, ?, ?> scope, Callable<? extends T> task) {
      this.scope = scope;
      this.task = task;
    }

    private void callTaskAndReportToScope() {
      Thread forkedThread = Thread.currentThread();
      try {
        State outcome;
        try {
          result = task.call();
          outcome = State.SUCCESS;
        } catch (Throwable e) {
          failure = e;
          outcome = State.FAILED;
        }
        cancelScopesLeftOpenByCurrentThread();
        scope.publishOutcomeAndNotifyJoiner(this, outcome);
      } catch (Throwable e) {
        forkedThread.getUncaughtExceptionHandler().uncaughtException(forkedThread, e);
      } finally {
        scope.removeThreadAndSignalIfNoneLeft(forkedThread);
      }
    }

    @Override
    public State state() {
      return state;
    }

    @Override
    public T get() {
      if (state != State.SUCCESS || !scope.canReadSubtaskOutcome()) {
        throw new IllegalStateException("Result not available");
      }
      return result;
    }

    @Override
    public Throwable exception() {
      if (state != State.FAILED || !scope.canReadSubtaskOutcome()) {
        throw new IllegalStateException("Exception not available");
      }
      return failure;
    }

    @Override
    public String toString() {
      String stateDescription = switch (state) {
        case UNAVAILABLE -> "Unavailable";
        case SUCCESS -> "Completed successfully";
        case FAILED -> "Failed: " + failure;
      };
      return "Subtask[" + stateDescription + "]";
    }
  }

  record ConfigImpl(ThreadFactory threadFactory, String name, Duration timeout)
      implements Configuration {

    static final ConfigImpl DEFAULT = new ConfigImpl(Thread.ofVirtual().factory(), null, null);

    @Override
    public Configuration withThreadFactory(ThreadFactory threadFactory) {
      return new ConfigImpl(Objects.requireNonNull(threadFactory), name, timeout);
    }

    @Override
    public Configuration withName(String name) {
      return new ConfigImpl(threadFactory, Objects.requireNonNull(name), timeout);
    }

    @Override
    public Configuration withTimeout(Duration timeout) {
      return new ConfigImpl(threadFactory, name, Objects.requireNonNull(timeout));
    }
  }

  private static final class TimeoutScheduler {
    static final ScheduledThreadPoolExecutor INSTANCE = createDaemonTimeoutExecutor();

    private static ScheduledThreadPoolExecutor createDaemonTimeoutExecutor() {
      var executor = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform()
          .name("structured-task-scope-timeout")
          .daemon()
          .factory());
      executor.setRemoveOnCancelPolicy(true);
      return executor;
    }
  }
}
