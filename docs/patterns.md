# Patterns

Each pattern below is taken from
[`UseCaseExamplesTest`](https://github.com/velo/structured-concurrency/blob/main/structured-concurrency/src/test/java/com/marvinformatics/structured/spec/UseCaseExamplesTest.java),
which runs in CI on JDK 21, 25 and 28. On JDK 28 it also runs against the real
`java.util.concurrent.StructuredTaskScope`, after the package rename. The name under each heading is
the test method that proves the pattern works.

The examples use these imports:

```java
import com.marvinformatics.structured.StructuredTaskScope;
import com.marvinformatics.structured.StructuredTaskScope.CancelledByTimeoutException;
import com.marvinformatics.structured.StructuredTaskScope.Joiner;
import com.marvinformatics.structured.StructuredTaskScope.Subtask;
```

## Fan out and combine

*Test: `fanOutAndCombineHeterogeneousResults`*

Run independent calls with different result types at once, and fail as a whole if any of them
fails.

```java
try (var scope = StructuredTaskScope.open()) {
  Subtask<User> user = scope.fork(() -> fetchUser("42"));
  Subtask<List<Order>> orders = scope.fork(() -> fetchOrders("42"));

  scope.join();
  return new Page(user.get(), orders.get());
}
```

## invokeAll, structured

*Tests: `invokeAllOrFailFastKeepsSubmissionOrder`, `invokeAllOrFailFastThrowsCompletionExceptionCausedByFailedTask`*

The structured version of `ExecutorService.invokeAll`: results in submission order, and the first
failure cancels the rest. Here the failure surfaces as an unchecked `CompletionException`.

```java
static <T> List<T> invokeAllOrFailFast(Collection<Callable<T>> tasks)
    throws InterruptedException {
  try (var scope = StructuredTaskScope.open(
      Joiner.<T, CompletionException>allSuccessfulOrThrow(CompletionException::new))) {
    tasks.forEach(scope::fork);
    return scope.join();
  }
}
```

## Race replicas

*Test: `raceReplicasAndTakeTheFastest`*

Ask several replicas and keep the first answer. The slower ones are interrupted as soon as one
succeeds.

```java
try (var scope = StructuredTaskScope.open(Joiner.<String>anySuccessfulOrThrow())) {
  scope.fork(() -> europe.lookup(key));
  scope.fork(() -> asia.lookup(key));
  scope.fork(() -> america.lookup(key));
  return scope.join();
}
```

## Hedged request

*Test: `hedgedRequestToleratesAFailingReplica`*

`anySuccessfulOrThrow` ignores failures while another subtask can still succeed, so a failing
primary doesn't fail the request.

```java
try (var scope = StructuredTaskScope.open(Joiner.<String>anySuccessfulOrThrow())) {
  scope.fork(() -> primary.fetch(key));
  scope.fork(() -> backup.fetch(key));
  return scope.join();
}
```

## Deadline with a fallback

*Test: `deadlineFallsBackToDefault`*

Give a slow call a time budget, and use a default when it runs out.

```java
String recommendation;
try (var scope = StructuredTaskScope.open(
    Joiner.<String>anySuccessfulOrThrow(),
    config -> config.withTimeout(Duration.ofMillis(50)))) {
  scope.fork(() -> personalizedRecommendation(user));
  recommendation = scope.join();
} catch (ExecutionException e) {
  if (!(e.getCause() instanceof CancelledByTimeoutException)) {
    throw e;
  }
  recommendation = "bestsellers";
}
```

## Scatter-gather with a deadline

*Test: `scatterGatherReturnsWhateverArrivedBeforeTheDeadline`*

Query every backend and keep whatever answered before the deadline. `allUntil(subtask -> false)`
never cancels early, and on timeout it returns the subtasks instead of throwing.

```java
List<String> results;
try (var scope = StructuredTaskScope.open(
    Joiner.<String>allUntil(subtask -> false),
    config -> config.withTimeout(Duration.ofMillis(500)))) {
  backends.forEach(backend -> scope.fork(() -> backend.search(query)));

  results = scope.join().stream()
      .filter(subtask -> subtask.state() == Subtask.State.SUCCESS)
      .map(Subtask::get)
      .toList();
}
```

## Give up after too many failures

*Test: `cancelAfterTooManyFailures`*

The `allUntil` predicate sees each finished subtask, and returning `true` cancels the scope. This
one tolerates one failure and stops at the second.

```java
class CancelAfterTwoFailures implements Predicate<Subtask<?>> {
  private final AtomicInteger failures = new AtomicInteger();

  @Override
  public boolean test(Subtask<?> subtask) {
    return subtask.state() == Subtask.State.FAILED && failures.incrementAndGet() >= 2;
  }
}

try (var scope = StructuredTaskScope.open(Joiner.<String>allUntil(new CancelAfterTwoFailures()))) {
  calls.forEach(call -> scope.fork(call));
  List<Subtask<String>> subtasks = scope.join();
}
```

## Quorum

*Test: `customJoinerWaitsForQuorum`*

Return as soon as enough replicas agree, and cancel the rest. This needs a custom joiner; the full
`Quorum` class is on the [Joiners](joiners.md#writing-your-own) page.

```java
try (var scope = StructuredTaskScope.open(new Quorum<String>(2))) {
  replicas.forEach(replica -> scope.fork(() -> replica.read(key)));
  List<String> agreeing = scope.join();
}
```

## Collect every failure

*Test: `customJoinerReportsEveryFailure`*

Validation usually wants every error, not the first one. This joiner never cancels, and throws one
exception with each failure attached as suppressed.

```java
final class FailureAggregatingJoiner implements Joiner<Object, Void, IllegalStateException> {
  private final Queue<Throwable> failures = new ConcurrentLinkedQueue<>();

  @Override
  public boolean onComplete(Subtask<Object> subtask) {
    if (subtask.state() == Subtask.State.FAILED) {
      failures.add(subtask.exception());
    }
    return false;
  }

  @Override
  public Void result() {
    if (!failures.isEmpty()) {
      var aggregateFailure = new IllegalStateException(failures.size() + " validations failed");
      failures.forEach(aggregateFailure::addSuppressed);
      throw aggregateFailure;
    }
    return null;
  }

  @Override
  public Void timeout() {
    throw new IllegalStateException(new CancelledByTimeoutException());
  }
}

try (var scope = StructuredTaskScope.open(new FailureAggregatingJoiner())) {
  scope.fork(() -> validateEmail(form));
  scope.fork(() -> validateAddress(form));
  scope.fork(() -> validateAge(form));
  scope.join();
}
```

## Translate failures into a domain exception

*Test: `translateFailuresIntoDomainException`*

Pass an exception function to a built-in joiner, so callers catch your exception instead of
`ExecutionException`.

```java
try (var scope = StructuredTaskScope.open(
    Joiner.<Object, PaymentDeclined>awaitAllSuccessfulOrThrow(PaymentDeclined::new))) {
  scope.fork(() -> fraudCheck(card));
  scope.fork(() -> charge(card));
  scope.join();
}
```

## Nested scopes

*Test: `nestedScopesFormATaskTree`*

A subtask can open its own scope. Failures and cancellation stay inside the right branch of the
tree.

```java
try (var scope = StructuredTaskScope.open()) {
  Subtask<User> user = scope.fork(() -> fetchUser("7"));
  Subtask<Integer> totalOrderAmount = scope.fork(() -> {
    try (var inner = StructuredTaskScope.open(Joiner.<List<Order>>allSuccessfulOrThrow())) {
      inner.fork(() -> fetchOrders("7-web"));
      inner.fork(() -> fetchOrders("7-store"));
      return inner.join().stream().flatMap(List::stream).mapToInt(Order::total).sum();
    }
  });

  scope.join();
  return new Summary(user.get(), totalOrderAmount.get());
}
```

## Divide and conquer

*Test: `divideAndConquerWithRecursiveScopes`*

Recursive scopes split work the way fork/join does, with virtual threads instead of a pool.

```java
static long parallelSum(long[] values, int from, int to) throws Exception {
  if (to - from <= 1_000) {
    long sum = 0;
    for (int i = from; i < to; i++) {
      sum += values[i];
    }
    return sum;
  }
  int middle = (from + to) >>> 1;
  try (var scope = StructuredTaskScope.open(Joiner.<Long>allSuccessfulOrThrow())) {
    scope.fork(() -> parallelSum(values, from, middle));
    scope.fork(() -> parallelSum(values, middle, to));
    return scope.join().stream().mapToLong(Long::longValue).sum();
  }
}
```

## Limit concurrency

*Test: `boundedConcurrencyWithSemaphore`*

Virtual threads are cheap, but the services they call may not be. A semaphore caps how many
subtasks do the work at once.

```java
var concurrencyLimiter = new Semaphore(3);
try (var scope = StructuredTaskScope.open(Joiner.<Integer>allSuccessfulOrThrow())) {
  for (Request request : requests) {
    scope.fork(() -> {
      concurrencyLimiter.acquire();
      try {
        return client.send(request);
      } finally {
        concurrencyLimiter.release();
      }
    });
  }
  List<Integer> statuses = scope.join();
}
```

## Interrupting the owner stops the tree

*Test: `interruptingTheOwnerCancelsTheWholeTree`*

Interrupting the thread that waits in `join()` makes `join()` throw `InterruptedException`. Closing
the scope then interrupts every subtask, so no work is left running.

```java
Thread owner = Thread.ofVirtual().start(() -> {
  try (var scope = StructuredTaskScope.open()) {
    scope.fork(() -> longRunningWork());
    scope.fork(() -> longRunningWork());
    scope.join();
  } catch (InterruptedException e) {
    // both subtasks have been interrupted and have finished by now
  } catch (ExecutionException e) {
    throw new IllegalStateException(e);
  }
});

owner.interrupt();
```

## Optional enrichment

*Test: `optionalEnrichmentDoesNotFailTheRequest`*

Some data is nice to have. With `allUntil`, a failing optional call doesn't fail the request, and
you check its state afterwards.

```java
try (var scope = StructuredTaskScope.open(Joiner.<Object>allUntil(subtask -> false))) {
  Subtask<User> user = scope.fork(() -> fetchUser("1"));
  Subtask<String> avatar = scope.fork(() -> fetchAvatarUrl("1"));
  scope.join();

  Optional<String> avatarUrl = avatar.state() == Subtask.State.SUCCESS
      ? Optional.of(avatar.get())
      : Optional.empty();
  return new Profile(user.get(), avatarUrl);
}
```
