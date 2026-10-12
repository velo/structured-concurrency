# Joiners

A `Joiner` is the policy of a scope. It sees every fork and every completion, may cancel the scope,
and decides what `join()` returns or throws. Pass one to `open`:

```java
try (var scope = StructuredTaskScope.open(Joiner.<String>anySuccessfulOrThrow())) { ... }
```

`open()` without a joiner uses `awaitAllSuccessfulOrThrow()`.

## Built-in joiners

| Factory | `join()` returns | Cancels the scope when | On failure |
|---|---|---|---|
| `awaitAllSuccessfulOrThrow()` | `null` | The first subtask fails | Throws `ExecutionException` |
| `allSuccessfulOrThrow()` | Every result, in fork order | The first subtask fails | Throws `ExecutionException` |
| `anySuccessfulOrThrow()` | The first successful result | The first subtask succeeds | Throws `ExecutionException` if every subtask failed, or if nothing was forked |
| `allUntil(predicate)` | Every forked `Subtask`, in fork order | `predicate` accepts a finished subtask | Never throws for a subtask failure |

The first failure is the one passed to the `ExecutionException`. On timeout, the throwing joiners
throw `ExecutionException` caused by `CancelledByTimeoutException`, and `allUntil` returns the
subtasks as they are.

### Choosing the exception

The three throwing factories also take a function that turns the failure into the exception
`join()` throws. Use it to throw a domain exception instead of `ExecutionException`:

```java
try (var scope = StructuredTaskScope.open(
    Joiner.<Object, PaymentDeclined>awaitAllSuccessfulOrThrow(PaymentDeclined::new))) {
  scope.fork(() -> fraudCheck(card));
  scope.fork(() -> charge(card));
  scope.join();
}
```

The function receives the subtask's exception, or a `CancelledByTimeoutException` on timeout, or a
`NoSuchElementException` when `anySuccessfulOrThrow` had nothing to join. It must not return
`null`.

## Writing your own

Implement `Joiner<T, R, R_X>`, where `T` is the subtask result type, `R` is what `join()` returns
and `R_X` is the exception `join()` throws. The four methods are:

| Method | Called | Return `true` to |
|---|---|---|
| `onFork(subtask)` | In the owner thread, for each fork, before the subtask starts | Cancel the scope |
| `onComplete(subtask)` | In the subtask's thread when it finishes, unless the scope is already cancelled | Cancel the scope |
| `result()` | By `join()` after the subtasks finished or the scope was cancelled | Not applicable: returns the outcome |
| `timeout()` | By `join()` when the timeout expired | Not applicable: returns the outcome |

`onFork` and `onComplete` have default implementations that return `false`. `onComplete` may read
the subtask's result or exception, even before the owner joins. Subtasks finish concurrently, so
use thread-safe state, and make a new joiner for every scope.

A joiner should only decide when the work is done and what `join()` returns. Business decisions,
such as which result is valid or what to do with it, belong in the code after `join()`.

A joiner that returns as soon as a quorum of subtasks has succeeded:

```java
final class Quorum<T> implements Joiner<T, List<T>, ExecutionException> {
  private final int requiredSuccessCount;
  private final Queue<T> results = new ConcurrentLinkedQueue<>();
  private final AtomicInteger successes = new AtomicInteger();

  Quorum(int requiredSuccessCount) {
    this.requiredSuccessCount = requiredSuccessCount;
  }

  @Override
  public boolean onComplete(Subtask<T> subtask) {
    if (subtask.state() != Subtask.State.SUCCESS) {
      return false;
    }
    results.add(subtask.get());
    return successes.incrementAndGet() >= requiredSuccessCount;
  }

  @Override
  public List<T> result() throws ExecutionException {
    if (successes.get() < requiredSuccessCount) {
      throw new ExecutionException(
          new NoSuchElementException("quorum of " + requiredSuccessCount + " not reached"));
    }
    return results.stream().limit(requiredSuccessCount).toList();
  }

  @Override
  public List<T> timeout() throws ExecutionException {
    throw new ExecutionException(new CancelledByTimeoutException());
  }
}
```

This joiner is tested in
[`UseCaseExamplesTest.customJoinerWaitsForQuorum`](https://github.com/velo/structured-concurrency/blob/main/structured-concurrency/src/test/java/com/marvinformatics/structured/spec/UseCaseExamplesTest.java).
[Patterns](patterns.md#collect-every-failure) has another custom joiner that reports every failure
at once.
