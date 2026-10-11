# Structured Concurrency Backport

The JDK 28 `StructuredTaskScope` API, as finalized by [JEP 543](https://openjdk.org/jeps/543),
packaged as a plain library for **JDK 21 or later, without `--enable-preview`**.

Structured concurrency treats a group of related tasks running in different threads as one unit of
work. You fork subtasks inside a scope, join them, and the scope makes sure none of them outlives
the block that started them. A failure can cancel the siblings, and an interrupt reaches the whole
tree.

```java
import com.marvinformatics.structured.StructuredTaskScope;
import com.marvinformatics.structured.StructuredTaskScope.Subtask;

try (var scope = StructuredTaskScope.open()) {
  Subtask<User> user = scope.fork(() -> findUser(id));
  Subtask<List<Order>> orders = scope.fork(() -> fetchOrders(id));

  scope.join();
  return new Page(user.get(), orders.get());
}
```

If either call fails, the other one is cancelled and `join()` throws `ExecutionException` with the
failure as its cause.

## Why a backport

Structured concurrency was in preview from JDK 21 to JDK 27 and becomes final in JDK 28. Many teams
run on the JDK 21 or 25 LTS and won't enable preview features in production. This library gives
them the **final** API now. When they move to JDK 28, the package changes from
`com.marvinformatics.structured` to `java.util.concurrent`, and that is the whole migration. An
[OpenRewrite recipe](migration.md#source-code-openrewrite) does it for source code, and an
[Eclipse Transformer rule](migration.md#compiled-code-eclipse-transformer) does it for compiled
jars.

## Where to go next

| Page | What it covers |
|---|---|
| [Getting started](getting-started.md) | Requirements, adding the dependency, a first scope |
| [Concepts](concepts.md) | Owners, subtasks, joining, cancellation, timeouts, nesting |
| [Joiners](joiners.md) | The built-in joiners and how to write your own |
| [Patterns](patterns.md) | Fan-out, races, hedging, deadlines, quorums and more, each with a test that runs it |
| [Migrating to the JDK](migration.md) | Moving source or compiled code to `java.util.concurrent` |
| [API reference](https://velo.github.io/structured-concurrency/api/) | The javadoc |

The [README](https://github.com/velo/structured-concurrency#readme) explains how the API was
derived from the JDK specification and how the project stays clear of the GPL-licensed OpenJDK
code.
