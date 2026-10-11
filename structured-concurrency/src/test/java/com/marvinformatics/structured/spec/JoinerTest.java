package com.marvinformatics.structured.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marvinformatics.structured.StructuredTaskScope;
import com.marvinformatics.structured.StructuredTaskScope.CancelledByTimeoutException;
import com.marvinformatics.structured.StructuredTaskScope.Joiner;
import com.marvinformatics.structured.StructuredTaskScope.Subtask;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class JoinerTest {

  @Test
  void allSuccessfulReturnsResultsInForkOrder() throws Exception {
    try (var scope = StructuredTaskScope.open(Joiner.<String>allSuccessfulOrThrow())) {
      scope.fork(() -> {
        Thread.sleep(Duration.ofMillis(50));
        return "first";
      });
      scope.fork(() -> "second");

      assertThat(scope.join()).containsExactly("first", "second");
    }
  }

  @Test
  void allSuccessfulUsesExceptionSupplier() throws Exception {
    try (var scope = StructuredTaskScope.open(
        Joiner.<String, CompletionException>allSuccessfulOrThrow(CompletionException::new))) {
      scope.fork(() -> {
        throw new IllegalArgumentException("bad");
      });

      assertThatThrownBy(scope::join)
          .isInstanceOf(CompletionException.class)
          .cause()
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void anySuccessfulReturnsFirstSuccessAndCancelsTheRest() throws Exception {
    try (var scope = StructuredTaskScope.open(Joiner.<String>anySuccessfulOrThrow())) {
      scope.fork(() -> {
        Thread.sleep(Duration.ofDays(1));
        return "slow";
      });
      scope.fork(() -> "fast");

      assertThat(scope.join()).isEqualTo("fast");
      assertThat(scope.isCancelled()).isTrue();
    }
  }

  @Test
  void anySuccessfulWithoutSubtasksFails() throws Exception {
    try (var scope = StructuredTaskScope.open(Joiner.<String>anySuccessfulOrThrow())) {
      assertThatThrownBy(scope::join)
          .isInstanceOf(ExecutionException.class)
          .cause()
          .isInstanceOf(NoSuchElementException.class);
    }
  }

  @Test
  void awaitAllSuccessfulReturnsNull() throws Exception {
    try (var scope = StructuredTaskScope.open(Joiner.<Object>awaitAllSuccessfulOrThrow())) {
      Subtask<String> text = scope.fork(() -> "text");
      Subtask<Integer> number = scope.fork(() -> 7);

      assertThat(scope.join()).isNull();
      assertThat(text.get()).isEqualTo("text");
      assertThat(number.get()).isEqualTo(7);
    }
  }

  @Test
  void allUntilGroupsSubtasksByState() throws Exception {
    try (var scope = StructuredTaskScope.open(Joiner.<String>allUntil(_ -> false))) {
      scope.fork(() -> "ok");
      scope.fork(() -> {
        throw new IllegalStateException();
      });

      Map<Subtask.State, List<Subtask<String>>> byState = scope.join().stream()
          .collect(Collectors.groupingBy(Subtask::state));

      assertThat(byState).containsOnlyKeys(Subtask.State.SUCCESS, Subtask.State.FAILED);
    }
  }

  @Test
  void allUntilReturnsSubtasksWhenTimeoutExpires() throws Exception {
    try (var scope = StructuredTaskScope.open(
        Joiner.<String>allUntil(_ -> false), cf -> cf.withTimeout(Duration.ofMillis(100)))) {
      Subtask<String> slow = scope.fork(() -> {
        Thread.sleep(Duration.ofDays(1));
        return "slow";
      });

      assertThat(scope.join()).containsExactly(slow);
      assertThat(slow.state()).isEqualTo(Subtask.State.UNAVAILABLE);
    }
  }

  @Test
  void timeoutIsReportedThroughExceptionSupplier() throws Exception {
    try (var scope = StructuredTaskScope.open(
        Joiner.<String, CompletionException>awaitAllSuccessfulOrThrow(CompletionException::new),
        cf -> cf.withTimeout(Duration.ZERO))) {
      scope.fork(() -> {
        Thread.sleep(Duration.ofDays(1));
        return "never";
      });

      assertThatThrownBy(scope::join)
          .isInstanceOf(CompletionException.class)
          .cause()
          .isInstanceOf(CancelledByTimeoutException.class);
    }
  }
}
