# Concepts

## The scope and its owner

A `StructuredTaskScope` is opened by one thread, its **owner**. Only the owner may fork, join and
close it. Calling those methods from another thread throws `WrongThreadException`.

A scope moves through a fixed lifecycle:

| Step | Method | Rules |
|---|---|---|
| Open | `StructuredTaskScope.open(...)` | The current thread becomes the owner |
| Fork | `fork(Callable)` or `fork(Runnable)` | Any number of times, before `join()` |
| Join | `join()` | Once. Forking after it throws `IllegalStateException` |
| Close | `close()` | Always, through try-with-resources |

If the owner forks subtasks but never calls `join()`, `close()` still cancels and waits for them,
then throws `IllegalStateException` to point out the bug.

## Subtasks

`fork` returns a `Subtask<T>`. Its `state()` is one of:

| State | Meaning | Read with |
|---|---|---|
| `UNAVAILABLE` | Still running, never ran because the scope was already cancelled, or finished after the scope was cancelled | Nothing to read |
| `SUCCESS` | Returned a value | `get()` |
| `FAILED` | Threw an exception | `exception()` |

`get()` and `exception()` throw `IllegalStateException` if the state doesn't match. They also throw
if the owner hasn't joined yet. The only place a result can be read before the join is the
joiner's `onComplete` method.

## Joining

`join()` waits until every subtask has finished or the scope is cancelled. It then returns or throws
whatever the scope's [joiner](joiners.md) decides. With the default joiner, it returns `null` when
everything succeeded and throws `ExecutionException` when something failed.

### Handling failures

Put the error handling in a `catch` on the try-with-resources, not in nested `try` blocks inside
the scope. By the time the `catch` runs, `close()` has already waited for every subtask, so nothing
is left running:

```java
try (var scope = StructuredTaskScope.open()) {
  Subtask<Customer> customer = scope.fork(() -> fetchCustomer(id));
  Subtask<Order> order = scope.fork(() -> fetchOrder(id));
  scope.join();
  return new OrderSummary(customer.get(), order.get());
} catch (ExecutionException e) {
  throw new OrderUnavailable(e.getCause());
}
```

If a failure is a normal outcome, such as "not found", consider returning it as a value from the
subtask rather than throwing it.

## Cancellation

Cancelling a scope interrupts every subtask thread that is still running, and stops new forks from
starting: a subtask forked after cancellation stays `UNAVAILABLE` and never runs. A scope is
cancelled when:

- its joiner asks for it, for example the default joiner after the first failure;
- its timeout expires;
- the owner closes it.

`isCancelled()` tells the owner whether it happened. A long loop of forks can check it to stop early.

### Interrupting the owner

If the owner is interrupted while it waits in `join()`, `join()` throws `InterruptedException`.
Closing the scope then interrupts the subtasks, so interrupting one thread stops the whole tree
below it. See the [pattern](patterns.md#interrupting-the-owner-stops-the-tree).

## Timeouts

`config.withTimeout(duration)` cancels the scope when the duration passes before `join()`
finishes. `join()` then returns what the joiner's `timeout()` method produces:

- the joiners that throw, such as `awaitAllSuccessfulOrThrow` and `anySuccessfulOrThrow`, throw
  `ExecutionException` caused by `CancelledByTimeoutException`;
- `allUntil` returns the subtasks as they are, so you can keep whatever finished in time.

The clock starts when `open` returns, not at the first fork, so it bounds the whole block up to
`join()`. A zero or negative timeout expires at once.

## Configuration

The operator passed to `open` receives the default configuration and returns a changed copy:

```java
StructuredTaskScope.open(joiner, config -> config
    .withName("checkout")
    .withThreadFactory(Thread.ofPlatform().factory())
    .withTimeout(Duration.ofSeconds(5)));
```

| Setting | Default | Effect |
|---|---|---|
| `withThreadFactory` | New unnamed virtual thread per fork | Creates the thread for each subtask |
| `withName` | None | Shown by the scope's `toString()` |
| `withTimeout` | None | Cancels the scope when it expires |

## Nesting

A subtask can open its own scope, and the scopes form a tree. Each scope must be closed before the
scope that encloses it. If the owner closes an outer scope while an inner one it opened is still
open, the inner scope is closed first and `close()` throws `StructureViolationException`.

A failure deep in the tree travels up as a chain of causes. When a subtask of an inner scope fails,
the inner `join()` throws, which fails the outer subtask that opened it, and the outer `join()`
throws in turn. Each level cancels its own siblings on the way. The outer `ExecutionException`'s
cause is the inner `ExecutionException`, whose cause is the original failure, so the stack trace
shows the path from the owner down to the subtask that failed.

## Differences from the JDK

A library can't reach some JDK internals:

| JDK behavior | Backport |
|---|---|
| Subtasks inherit `ScopedValue` bindings | Not supported: subtask threads start with no bindings |
| `fork` throws `StructureViolationException` when scoped value bindings changed since `open` | Not detected |
| Scopes appear as a tree in `jcmd Thread.dump_to_file -format=json` | Not visible |
| A thread that ends with scopes still open has them closed | Only for subtask threads started by a scope |

Everything else is checked against the JDK: OpenJDK's own 627 structured concurrency tests run
against the backport, on JDK 21, 25 and 28.
