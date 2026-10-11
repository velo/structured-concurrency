package com.marvinformatics.structured;

import java.io.Serial;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Runs a group of related subtasks, each in its own thread, as a single unit of work. This is the
 * entry point of the library: a copy of {@code java.util.concurrent.StructuredTaskScope} as
 * finalized by <a href="https://openjdk.org/jeps/543">JEP 543</a> in JDK 28, usable on JDK 25
 * without preview features. Code written against it moves to the JDK by changing the package
 * from {@code com.marvinformatics.structured} to {@code java.util.concurrent}.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>The thread that calls {@link #open()} owns the scope. Only the owner may fork, join and
 * close it. A scope is used in four steps, always inside try-with-resources:
 *
 * <ol>
 *   <li>{@linkplain #open(Joiner, UnaryOperator) open} it, choosing a {@link Joiner} that decides
 *       when the work is done and what {@link #join()} returns;
 *   <li>{@linkplain #fork(Callable) fork} subtasks, each started in a new thread;
 *   <li>{@linkplain #join() join}, which waits until every subtask finishes or the scope is
 *       cancelled, then returns or throws the joiner's outcome;
 *   <li>{@linkplain #close() close}, which cancels whatever is still running and waits for
 *       those threads to end.
 * </ol>
 *
 * <p>Because close waits for every thread, no subtask outlives the block that forked it. A failure
 * in one subtask can cancel its siblings, and interrupting the owner while it waits in join stops
 * the whole tree.
 *
 * <h2>Examples</h2>
 *
 * <p>Fork two calls and use both results. If either fails, the other is cancelled and {@code
 * join()} throws {@link ExecutionException} with the failure as its cause:
 *
 * {@snippet :
 * try (var scope = StructuredTaskScope.open()) {
 *   Subtask<User> user = scope.fork(() -> findUser(id));
 *   Subtask<List<Order>> orders = scope.fork(() -> fetchOrders(id));
 *   scope.join();
 *   return new Page(user.get(), orders.get());
 * }
 * }
 *
 * <p>Collect results of the same type, in fork order:
 *
 * {@snippet :
 * try (var scope = StructuredTaskScope.open(Joiner.<Price>allSuccessfulOrThrow())) {
 *   suppliers.forEach(supplier -> scope.fork(() -> supplier.quote(item)));
 *   List<Price> prices = scope.join();
 * }
 * }
 *
 * <p>Race replicas and keep the first answer. The others are cancelled as soon as one succeeds:
 *
 * {@snippet :
 * try (var scope = StructuredTaskScope.open(Joiner.<String>anySuccessfulOrThrow())) {
 *   replicas.forEach(replica -> scope.fork(() -> replica.lookup(key)));
 *   return scope.join();
 * }
 * }
 *
 * <p>Give the whole scope a deadline and keep whatever finished in time:
 *
 * {@snippet :
 * try (var scope = StructuredTaskScope.open(
 *     Joiner.<Result>allUntil(subtask -> false),
 *     config -> config.withTimeout(Duration.ofSeconds(2)))) {
 *   sources.forEach(source -> scope.fork(source::search));
 *   List<Result> arrived = scope.join().stream()
 *       .filter(subtask -> subtask.state() == Subtask.State.SUCCESS)
 *       .map(Subtask::get)
 *       .toList();
 * }
 * }
 *
 * <p>Translate failures into a domain exception instead of {@link ExecutionException}:
 *
 * {@snippet :
 * try (var scope = StructuredTaskScope.open(
 *     Joiner.<Void, PaymentDeclined>awaitAllSuccessfulOrThrow(PaymentDeclined::new))) {
 *   scope.fork(() -> charge(card));
 *   scope.fork(() -> reserve(stock));
 *   scope.join();
 * }
 * }
 *
 * <p>Scopes nest: a subtask may open its own scope, which forms a tree of tasks. An inner scope
 * must be closed before the scope that encloses it, otherwise closing the outer one throws
 * {@link StructureViolationException}.
 *
 * <h2>Differences from the JDK</h2>
 *
 * <p>Subtask threads do not inherit {@code ScopedValue} bindings. A change of bindings since the
 * scope was opened is not detected. Scopes do not appear in thread dumps.
 *
 * @param <T> the result type of subtasks forked in the scope
 * @param <R> the result type of {@link #join()}
 * @param <R_X> the exception type thrown by {@link #join()}
 */
public sealed interface StructuredTaskScope<T, R, R_X extends Throwable> extends AutoCloseable
    permits StructuredTaskScopeImpl {

  /**
   * Handle to a forked subtask, returned by {@link #fork(Callable)}. Its result or exception is
   * readable once the owner has joined, or from {@link Joiner#onComplete(Subtask)}.
   *
   * @param <T> the result type
   */
  sealed interface Subtask<T> extends Supplier<T> permits StructuredTaskScopeImpl.SubtaskImpl {

    /** The state of a subtask. */
    enum State {
      /**
       * Not finished, or finished after the scope was cancelled. A subtask forked after
       * cancellation never runs and stays in this state.
       */
      UNAVAILABLE,
      /** Finished with a result. */
      SUCCESS,
      /** Finished by throwing an exception. */
      FAILED,
    }

    /**
     * Returns the state of this subtask. Safe to call from any thread at any time.
     *
     * @return the current state
     */
    State state();

    /**
     * Returns the result of this subtask.
     *
     * @return the value returned by the task
     * @throws IllegalStateException if the state is not {@link State#SUCCESS}, or if the owner
     *     has not joined yet and the caller is not the joiner's {@code onComplete}
     */
    @Override
    T get();

    /**
     * Returns the exception thrown by this subtask.
     *
     * @return the exception thrown by the task
     * @throws IllegalStateException if the state is not {@link State#FAILED}, or if the owner has
     *     not joined yet and the caller is not the joiner's {@code onComplete}
     */
    Throwable exception();
  }

  /**
   * Policy that decides when a scope's work is done and what {@link #join()} produces. The scope
   * calls {@link #onFork(Subtask)} in the owner thread for each fork, and {@link
   * #onComplete(Subtask)} in the subtask's thread when it finishes. Either method returns {@code
   * true} to cancel the scope. Implementations must be thread safe.
   *
   * <p>A joiner that succeeds once two subtasks agree, and fails if too many fail:
   *
   * {@snippet :
   * class Quorum<T> implements Joiner<T, List<T>, ExecutionException> {
   *   private final Queue<T> votes = new ConcurrentLinkedQueue<>();
   *
   *   public boolean onComplete(Subtask<T> subtask) {
   *     if (subtask.state() == Subtask.State.SUCCESS) {
   *       votes.add(subtask.get());
   *     }
   *     return votes.size() >= 2;
   *   }
   *
   *   public List<T> result() throws ExecutionException {
   *     if (votes.size() < 2) {
   *       throw new ExecutionException(new IllegalStateException("No quorum"));
   *     }
   *     return List.copyOf(votes);
   *   }
   *
   *   public List<T> timeout() throws ExecutionException {
   *     return result();
   *   }
   * }
   * }
   *
   * <p>A joiner instance holds state, so use a new one for every scope.
   *
   * @param <T> the result type of subtasks forked in the scope
   * @param <R> the result type of {@link #join()}
   * @param <R_X> the exception type thrown by {@link #join()}
   */
  interface Joiner<T, R, R_X extends Throwable> {

    /**
     * Called by the owner each time it forks a subtask, before the subtask's thread starts.
     *
     * <p>The default implementation throws {@link IllegalArgumentException} if the subtask is
     * not {@link Subtask.State#UNAVAILABLE}, and returns {@code false}.
     *
     * @param subtask the subtask just forked
     * @return {@code true} to cancel the scope
     */
    default boolean onFork(Subtask<T> subtask) {
      if (subtask.state() != Subtask.State.UNAVAILABLE) {
        throw new IllegalArgumentException("Subtask not in UNAVAILABLE state");
      }
      return false;
    }

    /**
     * Called in a subtask's thread when that subtask finishes, unless the scope is already
     * cancelled. The subtask's result or exception is readable here.
     *
     * <p>The default implementation throws {@link IllegalArgumentException} if the subtask is
     * {@link Subtask.State#UNAVAILABLE}, and returns {@code false}.
     *
     * @param subtask the subtask that finished
     * @return {@code true} to cancel the scope
     */
    default boolean onComplete(Subtask<T> subtask) {
      if (subtask.state() == Subtask.State.UNAVAILABLE) {
        throw new IllegalArgumentException("Subtask has not completed");
      }
      return false;
    }

    /**
     * Produces the outcome of {@link #join()} once every subtask has finished or the scope was
     * cancelled.
     *
     * @return the value {@code join()} returns
     * @throws R_X the exception {@code join()} throws
     */
    R result() throws R_X;

    /**
     * Produces the outcome of {@link #join()} when the scope's timeout expired first.
     *
     * @return the value {@code join()} returns
     * @throws R_X the exception {@code join()} throws
     */
    R timeout() throws R_X;

    /**
     * Returns a joiner that collects every result in fork order, and cancels the scope on the
     * first failure.
     *
     * @param esf turns the first failure, or a {@link CancelledByTimeoutException}, into the
     *     exception {@code join()} throws
     * @param <T> the result type of subtasks
     * @param <R_X> the exception type thrown by {@code join()}
     * @return a new joiner
     */
    static <T, R_X extends Throwable> Joiner<T, List<T>, R_X> allSuccessfulOrThrow(
        Function<Throwable, R_X> esf) {
      return new Joiners.AllSuccessful<>(esf);
    }

    /**
     * Returns a joiner that collects every result in fork order, and cancels the scope on the
     * first failure. {@code join()} then throws {@link ExecutionException} with that failure as
     * its cause.
     *
     * @param <T> the result type of subtasks
     * @return a new joiner
     */
    static <T> Joiner<T, List<T>, ExecutionException> allSuccessfulOrThrow() {
      return allSuccessfulOrThrow(ExecutionException::new);
    }

    /**
     * Returns a joiner whose {@code join()} returns the first successful result, cancelling the
     * other subtasks. If every subtask fails, {@code join()} throws.
     *
     * @param esf turns a failure, a {@link java.util.NoSuchElementException} when nothing was
     *     forked, or a {@link CancelledByTimeoutException} into the exception {@code join()}
     *     throws
     * @param <T> the result type of subtasks
     * @param <R_X> the exception type thrown by {@code join()}
     * @return a new joiner
     */
    static <T, R_X extends Throwable> Joiner<T, T, R_X> anySuccessfulOrThrow(
        Function<Throwable, R_X> esf) {
      return new Joiners.AnySuccessful<>(esf);
    }

    /**
     * Returns a joiner whose {@code join()} returns the first successful result, cancelling the
     * other subtasks. If every subtask fails, {@code join()} throws {@link ExecutionException}.
     *
     * @param <T> the result type of subtasks
     * @return a new joiner
     */
    static <T> Joiner<T, T, ExecutionException> anySuccessfulOrThrow() {
      return anySuccessfulOrThrow(ExecutionException::new);
    }

    /**
     * Returns a joiner that waits for every subtask and cancels the scope on the first failure.
     * {@code join()} returns {@code null}; read results from the subtasks.
     *
     * @param esf turns the first failure, or a {@link CancelledByTimeoutException}, into the
     *     exception {@code join()} throws
     * @param <T> the result type of subtasks
     * @param <R_X> the exception type thrown by {@code join()}
     * @return a new joiner
     */
    static <T, R_X extends Throwable> Joiner<T, Void, R_X> awaitAllSuccessfulOrThrow(
        Function<Throwable, R_X> esf) {
      return new Joiners.AwaitAllSuccessful<>(esf);
    }

    /**
     * Returns a joiner that waits for every subtask and cancels the scope on the first failure,
     * which {@code join()} then throws as the cause of an {@link ExecutionException}. This is the
     * joiner of {@link StructuredTaskScope#open()}.
     *
     * @param <T> the result type of subtasks
     * @return a new joiner
     */
    static <T> Joiner<T, Void, ExecutionException> awaitAllSuccessfulOrThrow() {
      return awaitAllSuccessfulOrThrow(ExecutionException::new);
    }

    /**
     * Returns a joiner that waits until every subtask finishes or {@code isDone} accepts a
     * finished one, which cancels the scope. {@code join()} never throws for a subtask failure;
     * it returns every forked subtask, in fork order, including on timeout.
     *
     * @param isDone tested with each finished subtask; {@code true} cancels the scope
     * @param <T> the result type of subtasks
     * @return a new joiner
     */
    static <T> Joiner<T, List<Subtask<T>>, RuntimeException> allUntil(
        Predicate<? super Subtask<T>> isDone) {
      return new Joiners.AllUntil<>(isDone);
    }
  }

  /**
   * Thread factory, name and timeout used by a scope. Instances are immutable; each {@code with}
   * method returns a changed copy. Passed to the operator given to {@link #open(Joiner,
   * UnaryOperator)}:
   *
   * {@snippet :
   * StructuredTaskScope.open(joiner, config -> config
   *     .withName("checkout")
   *     .withThreadFactory(Thread.ofPlatform().factory())
   *     .withTimeout(Duration.ofSeconds(5)));
   * }
   *
   * <p>By default, subtasks run in new unnamed virtual threads, and there is no name or timeout.
   */
  sealed interface Configuration permits StructuredTaskScopeImpl.ConfigImpl {

    /**
     * Returns a copy that creates subtask threads with the given factory.
     *
     * @param threadFactory creates one thread per fork
     * @return a new configuration
     */
    Configuration withThreadFactory(ThreadFactory threadFactory);

    /**
     * Returns a copy with the given scope name, shown by the scope's {@code toString()}.
     *
     * @param name the scope name
     * @return a new configuration
     */
    Configuration withName(String name);

    /**
     * Returns a copy whose scope is cancelled when the timeout expires before {@code join()}
     * finishes. {@code join()} then returns {@link Joiner#timeout()}.
     *
     * @param timeout measured from {@code open}; zero or negative expires at once
     * @return a new configuration
     */
    Configuration withTimeout(Duration timeout);
  }

  /** Cause of the exception thrown by {@link #join()} when the scope timeout expires. */
  final class CancelledByTimeoutException extends RuntimeException {
    @Serial
    static final long serialVersionUID = 705788143955048766L;

    /** Creates the exception. */
    public CancelledByTimeoutException() {}
  }

  /**
   * Opens a scope owned by the current thread.
   *
   * @param joiner decides when the work is done and what {@link #join()} produces
   * @param configOperator changes the default {@link Configuration}
   * @param <T> the result type of subtasks
   * @param <R> the result type of {@code join()}
   * @param <R_X> the exception type thrown by {@code join()}
   * @return the new scope
   */
  static <T, R, R_X extends Throwable> StructuredTaskScope<T, R, R_X> open(
      Joiner<? super T, ? extends R, R_X> joiner,
      UnaryOperator<Configuration> configOperator) {
    return StructuredTaskScopeImpl.open(joiner, configOperator);
  }

  /**
   * Opens a scope owned by the current thread, with the default configuration.
   *
   * @param joiner decides when the work is done and what {@link #join()} produces
   * @param <T> the result type of subtasks
   * @param <R> the result type of {@code join()}
   * @param <R_X> the exception type thrown by {@code join()}
   * @return the new scope
   */
  static <T, R, R_X extends Throwable> StructuredTaskScope<T, R, R_X> open(
      Joiner<? super T, ? extends R, R_X> joiner) {
    return open(joiner, UnaryOperator.identity());
  }

  /**
   * Opens a scope owned by the current thread that waits for every subtask and fails on the
   * first failure, as {@link Joiner#awaitAllSuccessfulOrThrow()} does.
   *
   * @param configOperator changes the default {@link Configuration}
   * @param <T> the result type of subtasks
   * @return the new scope
   */
  static <T> StructuredTaskScope<T, Void, ExecutionException> open(
      UnaryOperator<Configuration> configOperator) {
    return open(Joiner.awaitAllSuccessfulOrThrow(), configOperator);
  }

  /**
   * Opens a scope owned by the current thread that waits for every subtask and fails on the
   * first failure, as {@link Joiner#awaitAllSuccessfulOrThrow()} does.
   *
   * @param <T> the result type of subtasks
   * @return the new scope
   */
  static <T> StructuredTaskScope<T, Void, ExecutionException> open() {
    return open(Joiner.awaitAllSuccessfulOrThrow(), UnaryOperator.identity());
  }

  /**
   * Starts {@code task} in a new thread of this scope. If the scope is already cancelled, the
   * task does not run and the subtask stays {@link Subtask.State#UNAVAILABLE}.
   *
   * @param task the work to run
   * @param <U> the task's result type
   * @return a handle to the subtask
   * @throws WrongThreadException if the caller is not the owner
   * @throws IllegalStateException if the scope is closed or the owner has called {@link #join()}
   * @throws RejectedExecutionException if the thread factory did not create a thread
   */
  <U extends T> Subtask<U> fork(Callable<? extends U> task);

  /**
   * Starts {@code task} in a new thread of this scope; its subtask's result is {@code null}.
   *
   * @param task the work to run
   * @param <U> the subtask's result type
   * @return a handle to the subtask
   * @throws WrongThreadException if the caller is not the owner
   * @throws IllegalStateException if the scope is closed or the owner has called {@link #join()}
   * @throws RejectedExecutionException if the thread factory did not create a thread
   * @see #fork(Callable)
   */
  <U extends T> Subtask<U> fork(Runnable task);

  /**
   * Waits until every subtask has finished or the scope is cancelled, then returns the joiner's
   * outcome. Call it once, after forking.
   *
   * @return the joiner's {@link Joiner#result()}, or its {@link Joiner#timeout()} if the timeout
   *     expired
   * @throws R_X as thrown by the joiner
   * @throws InterruptedException if the owner is interrupted before or while waiting
   * @throws WrongThreadException if the caller is not the owner
   * @throws IllegalStateException if the scope is closed or was already joined
   */
  R join() throws R_X, InterruptedException;

  /**
   * Tells whether the scope was cancelled by its joiner, its timeout, or by closing.
   *
   * @return {@code true} if cancelled
   */
  boolean isCancelled();

  /**
   * Cancels the scope if needed, interrupts the subtasks still running, and waits for their
   * threads to end. Closing again does nothing.
   *
   * @throws WrongThreadException if the caller is not the owner
   * @throws StructureViolationException if a scope opened later by the owner is still open; it
   *     is closed first
   * @throws IllegalStateException if the owner forked subtasks but never called {@link #join()}
   */
  @Override
  void close();
}
