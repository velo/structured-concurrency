# Structured Concurrency Backport

The JDK 28 `StructuredTaskScope` API, as finalized by [JEP 543](https://openjdk.org/jeps/543),
packaged as a plain library for **JDK 25 without `--enable-preview`**. It comes with tooling that
moves your code to the real JDK API when you upgrade, the way the Eclipse Transformer moved
`javax` code to `jakarta`.

```java
import com.marvinformatics.structured.StructuredTaskScope;
import com.marvinformatics.structured.StructuredTaskScope.Subtask;

try (var scope = StructuredTaskScope.open()) {
  Subtask<User> user = scope.fork(() -> findUser(id));
  Subtask<List<Order>> orders = scope.fork(() -> fetchOrders(id));

  scope.join();   // throws ExecutionException if either subtask fails, cancelling the other
  return new Page(user.get(), orders.get());
}
```

On JDK 28, the same code compiles with `java.util.concurrent` in place of
`com.marvinformatics.structured`. That package change is the whole migration, and both migration
tools here automate it.

## Why

Structured concurrency spent seven rounds in preview (JDK 21 to 27) and becomes final in JDK 28.
Many teams stay on the JDK 25 LTS and won't ship with preview features enabled. This library lets
them write structured concurrency code today, against the **final** API rather than a preview that
is about to change. When they move to JDK 28, a recipe or bytecode transform retargets the code.

## How this project started

Structured concurrency looks like a runtime feature, but most of it is an API: one scope type, a
few nested interfaces, and bookkeeping for the threads it starts. That makes it a candidate for the
treatment `java.time` got from the ThreeTen backport: ship the final API as a library now, and move
to the JDK's own copy later.

Before writing any code, we searched GitHub for an active backport of the final API. The closest
candidates were:

- [KuriosityRobotics/shuttle](https://github.com/KuriosityRobotics/shuttle), which is inactive and
  copies the JDK 21 preview API, not the final one;
- [Jox](https://github.com/softwaremill/jox) and [google/strands](https://github.com/google/strands),
  which offer structured concurrency through their own APIs, so code written for them cannot move
  to the JDK by a package rename.

None of them mirrors JEP 543, so this project was started with three goals: the final API on
JDK 25, tools that move code to the JDK, and tests that prove both.

## Keeping the license clean

OpenJDK is licensed under GPLv2 with the Classpath Exception, and this project is Apache 2.0.
Copying OpenJDK code into it would break that, so the project follows these steps:

- **Specification, not implementation.** The implementation was written from the public javadoc
  of `java.util.concurrent.StructuredTaskScope` and the JEP text. OpenJDK's
  `StructuredTaskScopeImpl.java` and `Joiners.java` were never used as a reference. An early
  download of the upstream directory brought them along, and they were deleted without being
  opened.
- **Signatures only where compatibility needs them.** Type names, method signatures and
  `serialVersionUID` values match the JDK, because source and binary migration depend on them.
  The javadoc is written in this project's own words.
- **Upstream tests are run, never shipped.** OpenJDK's tests are downloaded at build time into
  the `upstream-conformance` module, which publishes nothing. They are never committed.
- **Own tests and examples.** The tests and use-case examples were written for this project. The
  patterns they show are widely known, but the code is not copied from articles, the JEPs or
  OpenJDK.
- **Rules for future changes.** [AGENTS.md](AGENTS.md) repeats these rules for anyone, person or
  agent, who changes the code.

## How the API was derived

### 1. Choosing the target version

The API changed a lot during preview, so "copy the API" first means picking which version:

| JEP | JDK | Stage | Change that matters here |
|---|---|---|---|
| [428](https://openjdk.org/jeps/428) | 19 | Incubator | `jdk.incubator.concurrent`, subclassing (`ShutdownOnFailure`) |
| [437](https://openjdk.org/jeps/437) | 20 | Incubator | |
| [453](https://openjdk.org/jeps/453) | 21 | Preview | moved to `java.util.concurrent`; `fork` returns `Subtask` |
| 462, 480, 499 | 22–24 | Preview | |
| [505](https://openjdk.org/jeps/505) | 25 | Preview | **redesign**: `open()` factories and `Joiner` policies replace subclasses |
| [525](https://openjdk.org/jeps/525) | 26 | Preview | refinements |
| [533](https://openjdk.org/jeps/533) | 27 | Preview | `join()` throws `ExecutionException` instead of `FailedException`; third type parameter `R_X` for the `join()` exception; `Joiner.timeout()` |
| [543](https://openjdk.org/jeps/543) | 28 | **Final** | finalizes JEP 533 **without change** |

This library mirrors **JEP 543 / JEP 533**. Copying the JDK 25 shape instead would have made the
migration much harder: the tools would have to rewrite exception types and add a type parameter,
which is impractical at the bytecode level. Copying the final shape reduces the migration to a
package rename.

### 2. Taking the contract from the specification

The public types, member signatures and documented behavior come from the published
specification of `java.util.concurrent.StructuredTaskScope`: its javadoc at the pinned OpenJDK tag
(`openjdk.ref` in [`pom.xml`](pom.xml)), and the JEP text. That covers:

- the type shapes: `StructuredTaskScope<T, R, R_X>`, `Subtask` and its `State`, `Joiner`,
  `Configuration`, `CancelledByTimeoutException`, and `StructureViolationException`. The last one
  is still a preview API on JDK 25, so the library ships its own copy in the same package;
- every method signature, including generic bounds and thrown exceptions;
- the documented behavior: owner confinement, fork after join, join after interrupt, close
  without join, timeouts, structure violations, the `onComplete` visibility rules for
  `Subtask.get()`, and the exception supplying functions of the built-in joiners;
- the `@implSpec` of the `Joiner` default methods, which is reproduced as specified.

### 3. Writing the implementation independently

The implementation in
[`StructuredTaskScopeImpl`](structured-concurrency/src/main/java/com/marvinformatics/structured/StructuredTaskScopeImpl.java)
and [`Joiners`](structured-concurrency/src/main/java/com/marvinformatics/structured/Joiners.java)
was written from that contract. It does not use OpenJDK's implementation sources, which are GPLv2
with Classpath Exception. That is what makes the Apache 2.0 license possible. The JDK relies on the
internal `jdk.internal.misc.ThreadFlock`; this library uses a lock, a condition and a set of
threads per scope, plus a per-thread stack of open scopes to detect structure violations.

### 4. Proving it matches

Three independent checks keep the copy honest, and all of them can follow the JDK as it moves:

| Check | What it proves | Runs on |
|---|---|---|
| **Upstream conformance tests**: OpenJDK's own `StructuredTaskScopeTest` and `StressCancellation`, downloaded at build time from the pinned tag, with their imports pointed at this package | Behavior matches the JDK's own test suite, 627 tests | JDK 25 |
| **API alignment test** ([`ApiAlignmentTest`](jdk28/api-alignment/src/test/java/com/marvinformatics/structured/alignment/ApiAlignmentTest.java)): compares every public type with its JDK counterpart by reflection | Modifiers, sealed-ness, type parameters and bounds, supertypes, every constructor and method signature with generics and thrown exceptions, enum constants, nested types, `serialVersionUID` | JDK 28 |
| **Migration flows**: this project's own tests, migrated by each tool and run with the backport *removed from the classpath* | Code written against the backport behaves the same against the JDK after migration | JDK 28 |

The upstream tests are GPLv2 and are **not** part of this repository. The build downloads them into
`upstream-conformance/target/`, under a directory named after `openjdk.ref`, and that module is
never installed or published.

## Known differences

Some JDK behavior depends on runtime internals a library cannot reach:

| JDK behavior | Backport |
|---|---|
| Subtasks inherit `ScopedValue` bindings | Not supported: subtask threads start with no bindings |
| `fork` throws `StructureViolationException` when scoped value bindings changed since `open` | Not detected |
| Scopes appear as a tree in `jcmd Thread.dump_to_file -format=json` | Not visible |
| A thread that terminates with scopes still open has them closed | Only for subtask threads started by a scope |

This is why OpenJDK's `WithScopedValue` and `StructuredThreadDumpTest` are not part of the
conformance run.

## Migrating to the JDK

### Source: OpenRewrite

```shell
mvn org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.recipeArtifactCoordinates=com.marvinformatics.structured:structured-concurrency-rewrite:0.1.0 \
  -Drewrite.activeRecipes=com.marvinformatics.structured.MigrateToJdkStructuredConcurrency
```

`MigrateToJdkStructuredConcurrency` does both parts of the move. It renames the
`com.marvinformatics.structured` package to `java.util.concurrent` in Java sources, including
nested, wildcard and static imports, and it removes the backport dependency from `pom.xml`. Use
`UseJdkStructuredConcurrencyTypes` to change only the Java sources.

### Bytecode: Eclipse Transformer

To run already compiled code on the JDK, for example a dependency you cannot rebuild, use the
renames rule shipped in `structured-concurrency-transformer-rules`. Extract
`com/marvinformatics/structured/transformer/backport-to-jdk-package-renames.properties` from that
jar, then:

```shell
java -jar org.eclipse.transformer.cli-1.0.0.jar \
  -tr backport-to-jdk-package-renames.properties \
  app.jar app-jdk28.jar
```

The rule is one line: it renames exactly one package, `com.marvinformatics.structured`, to
`java.util.concurrent`. With the Maven plugin (`transformer-maven-plugin`), pass the file in
`<rules><renames>`, as [`jdk28/transform-flow`](jdk28/transform-flow/pom.xml) does.
Keep your own classes out of that package: the JVM refuses to load application classes in
`java.*`.

While structured concurrency is still preview in JDK 28 early-access builds, run the result with
`--enable-preview`.

## Building

| Command | What it does |
|---|---|
| `./mvnw verify` | JDK 25 build: library, recipes, transformer rules, our tests, and the upstream conformance tests (needs network) |
| `./mvnw verify -Dupstream.skip` | Same, without downloading the upstream tests |
| `scripts/verify-jdk28.sh` | Everything above, then API alignment and both migration flows on JDK 28, all in Docker |

The rewrite flow runs in two stages because OpenRewrite cannot run on JDK 28 yet: it rewrites on
JDK 25, then compiles and tests on JDK 28. That also matches how a real migration happens.

## Following the JDK

Two properties in [`pom.xml`](pom.xml) pin the upstream reference:

- `openjdk.ref`, the `openjdk/jdk` tag the conformance tests come from;
- `jdk28.image`, the JDK 28 Docker image used for verification.

Renovate opens a pull request whenever OpenJDK publishes a new build tag or image. If the JDK
changes the API or its behavior, the alignment test or the conformance tests fail in that pull
request and show exactly what moved. Once JDK 28 is GA and structured concurrency is no longer
preview, set `jdk28.preview-flag` to empty.

## Project layout

| Module | Purpose |
|---|---|
| `structured-concurrency` | The backport, its tests and the use-case examples |
| `upstream-conformance` | Runs OpenJDK's own tests against the backport; publishes nothing |
| `structured-concurrency-rewrite` | OpenRewrite recipes for source migration |
| `structured-concurrency-transformer-rules` | Eclipse Transformer rules for bytecode migration |
| `jdk28/api-alignment` | Reflection comparison against `java.util.concurrent` |
| `jdk28/transform-flow` | Runs the bytecode-transformed tests on the JDK |
| `jdk28/rewrite-flow` | Runs the OpenRewrite-migrated tests on the JDK |

The `jdk28` modules are only built with `-Pjdk28`.

## License

[Apache License 2.0](LICENSE). Not affiliated with Oracle or the OpenJDK project. Java and OpenJDK
are trademarks of Oracle and/or its affiliates.
