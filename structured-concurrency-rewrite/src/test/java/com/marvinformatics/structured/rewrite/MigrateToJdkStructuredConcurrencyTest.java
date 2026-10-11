package com.marvinformatics.structured.rewrite;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.maven.Assertions.pomXml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.JavaParser;
import org.openrewrite.maven.MavenExecutionContextView;
import org.openrewrite.maven.tree.MavenRepository;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

class MigrateToJdkStructuredConcurrencyTest implements RewriteTest {

  @Override
  public void defaults(RecipeSpec spec) {
    spec.recipeFromResources("com.marvinformatics.structured.MigrateToJdkStructuredConcurrency")
        .parser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
        .afterTypeValidationOptions(TypeValidation.none());
  }

  @Test
  void rewritesTopLevelAndNestedTypeImports() {
    rewriteRun(java(
        """
        import com.marvinformatics.structured.StructureViolationException;
        import com.marvinformatics.structured.StructuredTaskScope;
        import com.marvinformatics.structured.StructuredTaskScope.Joiner;
        import com.marvinformatics.structured.StructuredTaskScope.Subtask;
        import java.util.List;

        class Fetcher {
          List<String> fetch() throws InterruptedException {
            try (var scope = StructuredTaskScope.open(Joiner.<String, RuntimeException>allSuccessfulOrThrow(RuntimeException::new))) {
              Subtask<String> subtask = scope.fork(() -> "a");
              return scope.join();
            } catch (StructureViolationException e) {
              throw e;
            }
          }
        }
        """,
        """
        import java.util.concurrent.StructureViolationException;
        import java.util.concurrent.StructuredTaskScope;
        import java.util.concurrent.StructuredTaskScope.Joiner;
        import java.util.concurrent.StructuredTaskScope.Subtask;
        import java.util.List;

        class Fetcher {
          List<String> fetch() throws InterruptedException {
            try (var scope = StructuredTaskScope.open(Joiner.<String, RuntimeException>allSuccessfulOrThrow(RuntimeException::new))) {
              Subtask<String> subtask = scope.fork(() -> "a");
              return scope.join();
            } catch (StructureViolationException e) {
              throw e;
            }
          }
        }
        """));
  }

  @Test
  void rewritesOnlyOuterClassImportForOuterQualifiedNestedTypes() {
    rewriteRun(java(
        """
        import com.marvinformatics.structured.StructuredTaskScope;

        class Racer {
          String race() throws Exception {
            try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<String>anySuccessfulOrThrow())) {
              StructuredTaskScope.Subtask<String> subtask = scope.fork(() -> "fast");
              return scope.join();
            } catch (StructuredTaskScope.CancelledByTimeoutException e) {
              return "timeout";
            }
          }
        }
        """,
        """
        import java.util.concurrent.StructuredTaskScope;

        class Racer {
          String race() throws Exception {
            try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<String>anySuccessfulOrThrow())) {
              StructuredTaskScope.Subtask<String> subtask = scope.fork(() -> "fast");
              return scope.join();
            } catch (StructuredTaskScope.CancelledByTimeoutException e) {
              return "timeout";
            }
          }
        }
        """));
  }

  @Test
  void rewritesWildcardImport() {
    rewriteRun(java(
        """
        import com.marvinformatics.structured.*;

        class Runner {
          void run() throws Exception {
            try (StructuredTaskScope<Object, Void, ?> scope = StructuredTaskScope.open()) {
              scope.fork(() -> "a");
              scope.join();
            } catch (StructureViolationException e) {
              throw e;
            }
          }
        }
        """,
        """
        import java.util.concurrent.*;

        class Runner {
          void run() throws Exception {
            try (StructuredTaskScope<Object, Void, ?> scope = StructuredTaskScope.open()) {
              scope.fork(() -> "a");
              scope.join();
            } catch (StructureViolationException e) {
              throw e;
            }
          }
        }
        """));
  }

  @Test
  void rewritesStaticImport() {
    rewriteRun(java(
        """
        import static com.marvinformatics.structured.StructuredTaskScope.open;

        class Opener {
          void run() throws Exception {
            try (var scope = open()) {
              scope.join();
            }
          }
        }
        """,
        """
        import static java.util.concurrent.StructuredTaskScope.open;

        class Opener {
          void run() throws Exception {
            try (var scope = open()) {
              scope.join();
            }
          }
        }
        """));
  }

  @Test
  void leavesSubpackagesUntouched() {
    rewriteRun(java(
        """
        package com.marvinformatics.structured.spec;

        class Example {}
        """));
  }

  @Test
  void removesBackportDependencyFromPom(@TempDir Path repository) throws IOException {
    Path backportPom = repository.resolve(
        "com/marvinformatics/structured/structured-concurrency/0.1.0/structured-concurrency-0.1.0.pom");
    Files.createDirectories(backportPom.getParent());
    Files.writeString(backportPom, """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.marvinformatics.structured</groupId>
          <artifactId>structured-concurrency</artifactId>
          <version>0.1.0</version>
        </project>
        """);
    Files.writeString(backportPom.resolveSibling("structured-concurrency-0.1.0.jar"), "placeholder");
    var ctx = new InMemoryExecutionContext();
    MavenExecutionContextView.view(ctx).setRepositories(List.of(
        MavenRepository.builder().id("backport").uri(repository.toUri().toString()).knownToExist(true).build()));

    rewriteRun(
        spec -> spec.executionContext(ctx),
        pomXml(
            """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>app</artifactId>
              <version>1</version>
              <dependencies>
                <dependency>
                  <groupId>com.marvinformatics.structured</groupId>
                  <artifactId>structured-concurrency</artifactId>
                  <version>0.1.0</version>
                </dependency>
              </dependencies>
            </project>
            """,
            """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>app</artifactId>
              <version>1</version>
            </project>
            """));
  }
}
