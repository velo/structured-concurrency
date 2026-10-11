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

final class StructuredTaskScopeImpl<T, R, R_X extends Throwable>
    implements StructuredTaskScope<T, R, R_X> {

  private static final ThreadLocal<Deque<StructuredTaskScopeImpl<?, ?, ?>>> OPEN_SCOPES =
      ThreadLocal.withInitial(ArrayDeque::new);
  private static final ThreadLocal<StructuredTaskScopeImpl<?, ?, ?>> COMPLETING_IN =
      new ThreadLocal<>();

  private final Joiner<T, R, R_X> joiner;
  private final ConfigImpl config;
  private final Thread owner = Thread.currentThread();

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition stateChanged = lock.newCondition();
  private final Set<Thread> threads = new HashSet<>();
  private Future<?> timer;

  private volatile boolean cancelled;
  private volatile boolean timedOut;
  private volatile boolean joined;
  private boolean forked;
  private boolean joinAttempted;
  private boolean closed;
  private boolean detached;

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
    scope.startTimer();
    return scope;
  }

  private void startTimer() {
    Duration timeout = config.timeout();
    if (timeout == null) {
      return;
    }
    if (timeout.isPositive()) {
      timer = TimeoutScheduler.INSTANCE.schedule(
          this::expire, timeout.toNanos(), TimeUnit.NANOSECONDS);
    } else {
      expire();
    }
  }

  private void expire() {
    lock.lock();
    try {
      if (!joined && !closed) {
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
    ensureOwner();
    if (closed) {
      throw new IllegalStateException("Scope is closed");
    }
    if (joinAttempted) {
      throw new IllegalStateException("Already joined or join attempted");
    }

    var subtask = new SubtaskImpl<U>(this, task);
    @SuppressWarnings("unchecked")
    var asT = (Subtask<T>) (Subtask<?>) subtask;
    if (joiner.onFork(asT)) {
      cancel();
    }
    if (cancelled) {
      forked = true;
      return subtask;
    }

    Thread thread = config.threadFactory().newThread(subtask::run);
    if (thread == null) {
      throw new RejectedExecutionException("Thread factory did not create a thread");
    }
    forked = true;
    lock.lock();
    try {
      if (!cancelled) {
        threads.add(thread);
        thread.start();
      }
    } finally {
      lock.unlock();
    }
    return subtask;
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
    ensureOwner();
    lock.lock();
    try {
      if (closed) {
        throw new IllegalStateException("Scope is closed");
      }
      if (joined) {
        throw new IllegalStateException("Already joined");
      }
      joinAttempted = true;
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      while (!threads.isEmpty() && !cancelled) {
        stateChanged.await();
      }
      joined = true;
      cancelTimer();
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
    ensureOwner();
    if (closed) {
      return;
    }

    Deque<StructuredTaskScopeImpl<?, ?, ?>> openScopes = OPEN_SCOPES.get();
    boolean violation = false;
    if (!detached) {
      while (openScopes.peek() != this) {
        violation = true;
        openScopes.pop().detach();
      }
      openScopes.pop();
    }

    detach();
    lock.lock();
    try {
      closed = true;
    } finally {
      lock.unlock();
    }

    if (violation) {
      throw new StructureViolationException("Scope closed before nested scopes");
    }
    if (forked && !joinAttempted) {
      throw new IllegalStateException("Owner did not join after forking");
    }
  }

  private void detach() {
    detached = true;
    cancel();
    lock.lock();
    try {
      while (!threads.isEmpty()) {
        stateChanged.awaitUninterruptibly();
      }
      cancelTimer();
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

  private void cancelTimer() {
    if (timer != null) {
      timer.cancel(false);
      timer = null;
    }
  }

  private void ensureOwner() {
    if (Thread.currentThread() != owner) {
      throw new WrongThreadException("Current thread not owner");
    }
  }

  private void complete(SubtaskImpl<?> subtask, Object result, Throwable failure) {
    if (cancelled) {
      return;
    }
    subtask.complete(result, failure);
    COMPLETING_IN.set(this);
    try {
      @SuppressWarnings("unchecked")
      var asT = (Subtask<T>) subtask;
      if (joiner.onComplete(asT)) {
        cancel();
      }
    } finally {
      COMPLETING_IN.remove();
    }
  }

  private void threadFinished(Thread thread) {
    lock.lock();
    try {
      threads.remove(thread);
      if (threads.isEmpty()) {
        stateChanged.signalAll();
      }
    } finally {
      lock.unlock();
    }
  }

  private boolean outcomeVisible() {
    return joined || COMPLETING_IN.get() == this;
  }

  private static void closeLeftoverScopes() {
    Deque<StructuredTaskScopeImpl<?, ?, ?>> openScopes = OPEN_SCOPES.get();
    while (!openScopes.isEmpty()) {
      openScopes.pop().detach();
    }
    OPEN_SCOPES.remove();
  }

  @Override
  public String toString() {
    String state = closed ? "closed" : cancelled ? "cancelled" : "open";
    return "StructuredTaskScope[" + (config.name() != null ? config.name() + ", " : "") + state + "]";
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

    private void run() {
      Thread current = Thread.currentThread();
      try {
        Object value = null;
        Throwable thrown = null;
        try {
          value = task.call();
        } catch (Throwable e) {
          thrown = e;
        }
        closeLeftoverScopes();
        scope.complete(this, value, thrown);
      } catch (Throwable e) {
        current.getUncaughtExceptionHandler().uncaughtException(current, e);
      } finally {
        scope.threadFinished(current);
      }
    }

    @SuppressWarnings("unchecked")
    private void complete(Object value, Throwable thrown) {
      if (thrown == null) {
        result = (T) value;
        state = State.SUCCESS;
      } else {
        failure = thrown;
        state = State.FAILED;
      }
    }

    @Override
    public State state() {
      return state;
    }

    @Override
    public T get() {
      if (state != State.SUCCESS || !scope.outcomeVisible()) {
        throw new IllegalStateException("Result not available");
      }
      return result;
    }

    @Override
    public Throwable exception() {
      if (state != State.FAILED || !scope.outcomeVisible()) {
        throw new IllegalStateException("Exception not available");
      }
      return failure;
    }

    @Override
    public String toString() {
      String status = switch (state) {
        case UNAVAILABLE -> "Unavailable";
        case SUCCESS -> "Completed successfully";
        case FAILED -> "Failed: " + failure;
      };
      return "Subtask[" + status + "]";
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
    static final ScheduledThreadPoolExecutor INSTANCE = create();

    private static ScheduledThreadPoolExecutor create() {
      var executor = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform()
          .name("structured-task-scope-timeout")
          .daemon()
          .factory());
      executor.setRemoveOnCancelPolicy(true);
      return executor;
    }
  }
}
