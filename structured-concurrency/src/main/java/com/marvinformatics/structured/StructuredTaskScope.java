package com.marvinformatics.structured;

import java.io.Serial;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Source and behavior compatible copy of {@code java.util.concurrent.StructuredTaskScope} as
 * specified by <a href="https://openjdk.org/jeps/543">JEP 543</a>, usable on JDK 25 without
 * preview features. Code written against this type migrates to the JDK by changing the package
 * from {@code com.marvinformatics.structured} to {@code java.util.concurrent}.
 *
 * <p>Differences with the JDK implementation: subtask threads do not inherit {@link ScopedValue}
 * bindings, and scopes do not appear in thread dumps.
 *
 * @param <T> the result type of subtasks forked in the scope
 * @param <R> the result type of {@link #join()}
 * @param <R_X> the exception type thrown by {@link #join()}
 */
public sealed interface StructuredTaskScope<T, R, R_X extends Throwable> extends AutoCloseable
    permits StructuredTaskScopeImpl {

  /**
   * Handle to a forked subtask.
   *
   * @param <T> the result type
   */
  sealed interface Subtask<T> extends Supplier<T> permits StructuredTaskScopeImpl.SubtaskImpl {

    enum State {
      UNAVAILABLE,
      SUCCESS,
      FAILED,
    }

    State state();

    @Override
    T get();

    Throwable exception();
  }

  /**
   * Policy that observes forked and completed subtasks, may cancel the scope, and produces the
   * outcome of {@link #join()}. Implementations must be thread safe.
   *
   * @param <T> the result type of subtasks forked in the scope
   * @param <R> the result type of {@link #join()}
   * @param <R_X> the exception type thrown by {@link #join()}
   */
  interface Joiner<T, R, R_X extends Throwable> {

    default boolean onFork(Subtask<T> subtask) {
      if (subtask.state() != Subtask.State.UNAVAILABLE) {
        throw new IllegalArgumentException("Subtask not in UNAVAILABLE state");
      }
      return false;
    }

    default boolean onComplete(Subtask<T> subtask) {
      if (subtask.state() == Subtask.State.UNAVAILABLE) {
        throw new IllegalArgumentException("Subtask has not completed");
      }
      return false;
    }

    R result() throws R_X;

    R timeout() throws R_X;

    static <T, R_X extends Throwable> Joiner<T, List<T>, R_X> allSuccessfulOrThrow(
        Function<Throwable, R_X> esf) {
      return new Joiners.AllSuccessful<>(esf);
    }

    static <T> Joiner<T, List<T>, ExecutionException> allSuccessfulOrThrow() {
      return allSuccessfulOrThrow(ExecutionException::new);
    }

    static <T, R_X extends Throwable> Joiner<T, T, R_X> anySuccessfulOrThrow(
        Function<Throwable, R_X> esf) {
      return new Joiners.AnySuccessful<>(esf);
    }

    static <T> Joiner<T, T, ExecutionException> anySuccessfulOrThrow() {
      return anySuccessfulOrThrow(ExecutionException::new);
    }

    static <T, R_X extends Throwable> Joiner<T, Void, R_X> awaitAllSuccessfulOrThrow(
        Function<Throwable, R_X> esf) {
      return new Joiners.AwaitAllSuccessful<>(esf);
    }

    static <T> Joiner<T, Void, ExecutionException> awaitAllSuccessfulOrThrow() {
      return awaitAllSuccessfulOrThrow(ExecutionException::new);
    }

    static <T> Joiner<T, List<Subtask<T>>, RuntimeException> allUntil(
        Predicate<? super Subtask<T>> isDone) {
      return new Joiners.AllUntil<>(isDone);
    }
  }

  /** Thread factory, name and timeout used by a scope. */
  sealed interface Configuration permits StructuredTaskScopeImpl.ConfigImpl {

    Configuration withThreadFactory(ThreadFactory threadFactory);

    Configuration withName(String name);

    Configuration withTimeout(Duration timeout);
  }

  /** Cause of the exception thrown by {@link #join()} when the scope timeout expires. */
  final class CancelledByTimeoutException extends RuntimeException {
    @Serial
    static final long serialVersionUID = 705788143955048766L;

    public CancelledByTimeoutException() {}
  }

  static <T, R, R_X extends Throwable> StructuredTaskScope<T, R, R_X> open(
      Joiner<? super T, ? extends R, R_X> joiner,
      UnaryOperator<Configuration> configOperator) {
    return StructuredTaskScopeImpl.open(joiner, configOperator);
  }

  static <T, R, R_X extends Throwable> StructuredTaskScope<T, R, R_X> open(
      Joiner<? super T, ? extends R, R_X> joiner) {
    return open(joiner, UnaryOperator.identity());
  }

  static <T> StructuredTaskScope<T, Void, ExecutionException> open(
      UnaryOperator<Configuration> configOperator) {
    return open(Joiner.awaitAllSuccessfulOrThrow(), configOperator);
  }

  static <T> StructuredTaskScope<T, Void, ExecutionException> open() {
    return open(Joiner.awaitAllSuccessfulOrThrow(), UnaryOperator.identity());
  }

  <U extends T> Subtask<U> fork(Callable<? extends U> task);

  <U extends T> Subtask<U> fork(Runnable task);

  R join() throws R_X, InterruptedException;

  boolean isCancelled();

  @Override
  void close();
}
