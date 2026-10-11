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

final class Joiners {

  private Joiners() {}

  private static void requireUnavailable(Subtask<?> subtask) {
    if (subtask.state() != Subtask.State.UNAVAILABLE) {
      throw new IllegalArgumentException("Subtask not in UNAVAILABLE state");
    }
  }

  private static void requireCompleted(Subtask<?> subtask) {
    if (subtask.state() == Subtask.State.UNAVAILABLE) {
      throw new IllegalArgumentException("Subtask has not completed");
    }
  }

  private static <R_X extends Throwable> R_X exception(
      Function<Throwable, R_X> esf, Throwable cause) {
    return Objects.requireNonNull(esf.apply(cause), "exception supplying function returned null");
  }

  private abstract static class FailFast<T, R, R_X extends Throwable> implements Joiner<T, R, R_X> {
    private final Function<Throwable, R_X> esf;
    private final AtomicReference<Throwable> firstFailure = new AtomicReference<>();

    FailFast(Function<Throwable, R_X> esf) {
      this.esf = Objects.requireNonNull(esf);
    }

    @Override
    public boolean onFork(Subtask<T> subtask) {
      requireUnavailable(subtask);
      return false;
    }

    @Override
    public boolean onComplete(Subtask<T> subtask) {
      requireCompleted(subtask);
      return subtask.state() == Subtask.State.FAILED
          && firstFailure.compareAndSet(null, subtask.exception());
    }

    @Override
    public R result() throws R_X {
      Throwable failure = firstFailure.get();
      if (failure != null) {
        throw exception(esf, failure);
      }
      return success();
    }

    @Override
    public R timeout() throws R_X {
      throw exception(esf, new CancelledByTimeoutException());
    }

    abstract R success();
  }

  static final class AllSuccessful<T, R_X extends Throwable> extends FailFast<T, List<T>, R_X> {
    private final List<Subtask<T>> subtasks = new ArrayList<>();

    AllSuccessful(Function<Throwable, R_X> esf) {
      super(esf);
    }

    @Override
    public boolean onFork(Subtask<T> subtask) {
      super.onFork(subtask);
      subtasks.add(subtask);
      return false;
    }

    @Override
    List<T> success() {
      return subtasks.stream().map(Subtask::get).toList();
    }
  }

  static final class AwaitAllSuccessful<T, R_X extends Throwable> extends FailFast<T, Void, R_X> {
    AwaitAllSuccessful(Function<Throwable, R_X> esf) {
      super(esf);
    }

    @Override
    Void success() {
      return null;
    }
  }

  static final class AnySuccessful<T, R_X extends Throwable> implements Joiner<T, T, R_X> {
    private final Function<Throwable, R_X> esf;
    private final AtomicReference<Subtask<T>> firstSuccess = new AtomicReference<>();
    private final AtomicReference<Throwable> firstFailure = new AtomicReference<>();

    AnySuccessful(Function<Throwable, R_X> esf) {
      this.esf = Objects.requireNonNull(esf);
    }

    @Override
    public boolean onFork(Subtask<T> subtask) {
      requireUnavailable(subtask);
      return false;
    }

    @Override
    public boolean onComplete(Subtask<T> subtask) {
      requireCompleted(subtask);
      if (subtask.state() == Subtask.State.SUCCESS) {
        return firstSuccess.compareAndSet(null, subtask);
      }
      firstFailure.compareAndSet(null, subtask.exception());
      return false;
    }

    @Override
    public T result() throws R_X {
      Subtask<T> success = firstSuccess.get();
      if (success != null) {
        return success.get();
      }
      Throwable failure = firstFailure.get();
      throw exception(esf, failure != null ? failure : new NoSuchElementException("No subtasks"));
    }

    @Override
    public T timeout() throws R_X {
      throw exception(esf, new CancelledByTimeoutException());
    }
  }

  static final class AllUntil<T> implements Joiner<T, List<Subtask<T>>, RuntimeException> {
    private final Predicate<? super Subtask<T>> isDone;
    private final List<Subtask<T>> subtasks = new ArrayList<>();

    AllUntil(Predicate<? super Subtask<T>> isDone) {
      this.isDone = Objects.requireNonNull(isDone);
    }

    @Override
    public boolean onFork(Subtask<T> subtask) {
      requireUnavailable(subtask);
      subtasks.add(subtask);
      return false;
    }

    @Override
    public boolean onComplete(Subtask<T> subtask) {
      requireCompleted(subtask);
      return isDone.test(subtask);
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
