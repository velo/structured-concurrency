# Getting started

## Requirements

- JDK 21 or later. Subtasks run in virtual threads, which are final from JDK 21.
- No `--enable-preview` flag.

## Adding the dependency

=== "Maven"

    ```xml
    <dependency>
      <groupId>com.marvinformatics.structured</groupId>
      <artifactId>structured-concurrency</artifactId>
      <version>0.1.0</version>
    </dependency>
    ```

=== "Gradle"

    ```kotlin
    implementation("com.marvinformatics.structured:structured-concurrency:0.1.0")
    ```

The jar declares the automatic module name `com.marvinformatics.structured`.

## A first scope

Every scope follows the same four steps: open, fork, join, close. Try-with-resources does the
close.

```java
import com.marvinformatics.structured.StructuredTaskScope;
import com.marvinformatics.structured.StructuredTaskScope.Subtask;

Page loadPage(String id) throws ExecutionException, InterruptedException {
  try (var scope = StructuredTaskScope.open()) {                     // 1. open
    Subtask<User> user = scope.fork(() -> findUser(id));            // 2. fork
    Subtask<List<Order>> orders = scope.fork(() -> fetchOrders(id));

    scope.join();                                                    // 3. join
    return new Page(user.get(), orders.get());
  }                                                                  // 4. close
}
```

1. **Open.** `StructuredTaskScope.open()` creates a scope owned by the current thread. With no
   arguments it waits for every subtask and fails as soon as one fails.
2. **Fork.** Each `fork` starts the task in a new virtual thread and returns a `Subtask` handle.
3. **Join.** `join()` waits for the subtasks. If one failed, it throws `ExecutionException` with
   that failure as the cause, after cancelling the others.
4. **Close.** Leaving the `try` block closes the scope. Closing cancels anything still running and
   waits until every subtask thread has ended, so no thread outlives the block.

After `join()` returns, `user.get()` and `orders.get()` give the results. Reading a result before
joining throws `IllegalStateException`.

## Choosing what join returns

`open()` takes an optional [joiner](joiners.md) that decides when the work is done and what
`join()` returns. To collect results of the same type in fork order:

```java
try (var scope = StructuredTaskScope.open(Joiner.<Price>allSuccessfulOrThrow())) {
  suppliers.forEach(supplier -> scope.fork(() -> supplier.quote(item)));
  List<Price> prices = scope.join();
}
```

To take the first successful answer and cancel the rest:

```java
try (var scope = StructuredTaskScope.open(Joiner.<String>anySuccessfulOrThrow())) {
  replicas.forEach(replica -> scope.fork(() -> replica.lookup(key)));
  return scope.join();
}
```

## Adding a timeout

The second argument to `open` changes the scope's configuration. A timeout cancels the scope if the
work takes too long:

```java
try (var scope = StructuredTaskScope.open(
    Joiner.<String>anySuccessfulOrThrow(),
    config -> config.withTimeout(Duration.ofSeconds(2)))) {
  ...
}
```

When the timeout expires, `join()` throws `ExecutionException` caused by
`CancelledByTimeoutException`. See [Concepts](concepts.md#timeouts) for details and
[Patterns](patterns.md#deadline-with-a-fallback) for a fallback example.
