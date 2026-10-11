package com.marvinformatics.structured;

import com.marvinformatics.structured.StructuredTaskScope.CancelledByTimeoutException;
import com.marvinformatics.structured.StructuredTaskScope.Joiner;
import com.marvinformatics.structured.StructuredTaskScope.Subtask;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

/** The built-in joiners returned by the static factories of {@link Joiner}. */
final class Joiners {

  private Joiners() {}

  private abstract static class ThrowingJoiner<T, R, R_X extends Throwable>
      implements Joiner<T, R, R_X> {
    private final Function<Throwable, R_X> exceptionFactory;

    ThrowingJoiner(Function<Throwable, R_X> exceptionFactory) {
      this.exceptionFactory = Objects.requireNonNull(exceptionFactory);
    }

    R_X mapException(Throwable cause) {
      return Objects.requireNonNull(
          exceptionFactory.apply(cause), "exception supplying function returned null");
    }

    @Override
    public R timeout() throws R_X {
      throw mapException(new CancelledByTimeoutException());
    }
  }

  private abstract static class FailFast<T, R, R_X extends Throwable>
      extends ThrowingJoiner<T, R, R_X> {
    private final AtomicReference<Throwable> firstFailure = new AtomicReference<>();

    FailFast(Function<Throwable, R_X> exceptionFactory) {
      super(exceptionFactory);
    }

    @Override
    public boolean onComplete(Subtask<T> subtask) {
      super.onComplete(subtask);
      return subtask.state() == Subtask.State.FAILED
          && firstFailure.compareAndSet(null, subtask.exception());
    }

    @Override
    public R result() throws R_X {
      Throwable failure = firstFailure.get();
      if (failure != null) {
        throw mapException(failure);
      }
      return collectSuccessfulResult();
    }

    abstract R collectSuccessfulResult();
  }

  static final class AllSuccessful<T, R_X extends Throwable> extends FailFast<T, List<T>, R_X> {
    private final List<Subtask<T>> subtasks = new ArrayList<>();

    AllSuccessful(Function<Throwable, R_X> exceptionFactory) {
      super(exceptionFactory);
    }

    @Override
    public boolean onFork(Subtask<T> subtask) {
      super.onFork(subtask);
      subtasks.add(subtask);
      return false;
    }

    @Override
    List<T> collectSuccessfulResult() {
      return subtasks.stream().map(Subtask::get).toList();
    }
  }

  static final class AwaitAllSuccessful<T, R_X extends Throwable> extends FailFast<T, Void, R_X> {
    AwaitAllSuccessful(Function<Throwable, R_X> exceptionFactory) {
      super(exceptionFactory);
    }

    @Override
    Void collectSuccessfulResult() {
      return null;
    }
  }

  static final class AnySuccessful<T, R_X extends Throwable> extends ThrowingJoiner<T, T, R_X> {
    private final AtomicReference<Subtask<T>> firstSuccess = new AtomicReference<>();
    private final AtomicReference<Throwable> firstFailure = new AtomicReference<>();

    AnySuccessful(Function<Throwable, R_X> exceptionFactory) {
      super(exceptionFactory);
    }

    @Override
    public boolean onComplete(Subtask<T> subtask) {
      super.onComplete(subtask);
      if (subtask.state() == Subtask.State.SUCCESS) {
        return firstSuccess.compareAndSet(null, subtask);
      }
      firstFailure.compareAndSet(null, subtask.exception());
      return false;
    }

    @Override
    public T result() throws R_X {
      Subtask<T> firstSuccessfulSubtask = firstSuccess.get();
      if (firstSuccessfulSubtask != null) {
        return firstSuccessfulSubtask.get();
      }
      Throwable failure = firstFailure.get();
      throw mapException(failure != null ? failure : new NoSuchElementException("No subtasks"));
    }
  }

  static final class AllUntil<T> implements Joiner<T, List<Subtask<T>>, RuntimeException> {
    private final Predicate<? super Subtask<T>> shouldCancelScope;
    private final List<Subtask<T>> subtasks = new ArrayList<>();

    AllUntil(Predicate<? super Subtask<T>> shouldCancelScope) {
      this.shouldCancelScope = Objects.requireNonNull(shouldCancelScope);
    }

    @Override
    public boolean onFork(Subtask<T> subtask) {
      Joiner.super.onFork(subtask);
      subtasks.add(subtask);
      return false;
    }

    @Override
    public boolean onComplete(Subtask<T> subtask) {
      Joiner.super.onComplete(subtask);
      return shouldCancelScope.test(subtask);
    }

    @Override
    public List<Subtask<T>> result() {
      return List.copyOf(subtasks);
    }

    @Override
    public List<Subtask<T>> timeout() {
      return result();
    }
  }
}
