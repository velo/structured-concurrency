package com.marvinformatics.structured.rewrite;

import static org.openrewrite.java.Assertions.java;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

class MigrateToJdkStructuredConcurrencyTest implements RewriteTest {

  @Override
  public void defaults(RecipeSpec spec) {
    spec.recipeFromResources("com.marvinformatics.structured.MigrateToJdkStructuredConcurrency")
        .parser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()))
        .typeValidationOptions(TypeValidation.none());
  }

  @Test
  void rewritesTopLevelAndNestedTypeImports() {
    rewriteRun(java(
        """
        import com.marvinformatics.structured.StructureViolationException;
        import com.marvinformatics.structured.StructuredTaskScope;
        import com.marvinformatics.structured.StructuredTaskScope.Joiner;
        import com.marvinformatics.structured.StructuredTaskScope.Subtask;
        
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
  void rewritesQualifiedNestedTypeReferences() {
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
}
