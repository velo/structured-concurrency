# Migrating to the JDK

The backport has the same types, methods and behavior as `java.util.concurrent.StructuredTaskScope`
in JDK 28. Moving to the JDK means changing the package from `com.marvinformatics.structured` to
`java.util.concurrent` and dropping the dependency. Two tools do it for you.

## Source code: OpenRewrite

Run the recipe on a Maven project:

```shell
mvn org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.recipeArtifactCoordinates=com.marvinformatics.structured:structured-concurrency-rewrite:0.1.0 \
  -Drewrite.activeRecipes=com.marvinformatics.structured.MigrateToJdkStructuredConcurrency
```

| Recipe | What it changes |
|---|---|
| `MigrateToJdkStructuredConcurrency` | Java sources and `pom.xml` |
| `UseJdkStructuredConcurrencyTypes` | Java sources only |

The Java change renames the package everywhere it appears: regular, nested, wildcard and static
imports, and qualified names such as `StructuredTaskScope.Joiner`. Subpackages are left alone.
`MigrateToJdkStructuredConcurrency` also removes the
`com.marvinformatics.structured:structured-concurrency` dependency.

Before:

```java
import com.marvinformatics.structured.StructuredTaskScope;
import com.marvinformatics.structured.StructuredTaskScope.Joiner;
```

After:

```java
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
```

!!! warning "Run OpenRewrite on JDK 21 or 25"
    OpenRewrite cannot run on JDK 28 builds yet. Run the recipe on your current JDK, then switch
    the build to JDK 28.

## Compiled code: Eclipse Transformer

To run a jar you can't rebuild on the JDK, rewrite its bytecode with the rule shipped in
`structured-concurrency-transformer-rules`. Extract
`com/marvinformatics/structured/transformer/backport-to-jdk-package-renames.properties` from that
jar, then:

```shell
java -jar org.eclipse.transformer.cli-1.0.0.jar \
  -tr backport-to-jdk-package-renames.properties \
  app.jar app-jdk28.jar
```

The rule is one line, `com.marvinformatics.structured=java.util.concurrent`. With the Maven plugin
(`transformer-maven-plugin`), pass the file in `<rules><renames>`.

## What is checked

Both tools are tested on every build. The project's own tests are migrated by each tool and then run
on JDK 28 with the backport removed from the classpath. A reflection test also compares every
public type, method, generic signature and `serialVersionUID` of the backport with the JDK.

## Keep your code out of the backport package

Both tools rename `com.marvinformatics.structured` itself. Don't put your own classes in that
package: after the rename they would land in `java.util.concurrent`, where the JVM refuses to load
application classes.

## Preview on JDK 28 early-access builds

Until JDK 28 is released, structured concurrency is still a preview API in its early-access builds.
Run migrated code there with `--enable-preview`.
