package com.marvinformatics.structured.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.marvinformatics.structured.StructureViolationException;
import com.marvinformatics.structured.StructuredTaskScope;
import com.marvinformatics.structured.StructuredTaskScope.CancelledByTimeoutException;
import com.marvinformatics.structured.StructuredTaskScope.Joiner;
import com.marvinformatics.structured.StructuredTaskScope.Subtask;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ScopeLifecycleTest {

  @Test
  void joinWaitsForAllSubtasks() throws Exception {
    try (var scope = StructuredTaskScope.open()) {
      Subtask<String> slow = scope.fork(() -> {
        Thread.sleep(Duration.ofMillis(50));
        return "slow";
      });
      Subtask<Integer> fast = scope.fork(() -> 42);

      assertThat(scope.join()).isNull();
      assertThat(slow.get()).isEqualTo("slow");
      assertThat(fast.get()).isEqualTo(42);
    }
  }

  @Test
  void defaultThreadFactoryCreatesUnnamedVirtualThreads() throws Exception {
    try (var scope = StructuredTaskScope.open()) {
      Subtask<Thread> subtask = scope.fork(Thread::currentThread);
      scope.join();

      assertThat(subtask.get().isVirtual()).isTrue();
      assertThat(subtask.get().getName()).isEmpty();
    }
  }

  @Test
  void configuredThreadFactoryIsUsed() throws Exception {
    var factory = Thread.ofPlatform().name("duke-", 0).factory();
    try (var scope = StructuredTaskScope.open(cf -> cf.withThreadFactory(factory))) {
      Subtask<String> subtask = scope.fork(() -> Thread.currentThread().getName());
      scope.join();

      assertThat(subtask.get()).isEqualTo("duke-0");
    }
  }

  @Test
  void firstFailureCancelsSiblingsAndFailsJoin() throws Exception {
    var siblingInterrupted = new CountDownLatch(1);
    try (var scope = StructuredTaskScope.open()) {
      scope.fork(() -> {
        try {
          Thread.sleep(Duration.ofDays(1));
        } catch (InterruptedException e) {
          siblingInterrupted.countDown();
        }
      });
      scope.fork(() -> {
        throw new IllegalStateException("boom");
      });

      assertThatThrownBy(scope::join)
          .isInstanceOf(ExecutionException.class)
          .cause()
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("boom");
      assertThat(scope.isCancelled()).isTrue();
    }
    assertThat(siblingInterrupted.getCount()).isZero();
  }

  @Test
  void timeoutCancelsScope() throws Exception {
    try (var scope = StructuredTaskScope.open(cf -> cf.withTimeout(Duration.ofMillis(100)))) {
      Subtask<String> subtask = scope.fork(() -> {
        Thread.sleep(Duration.ofDays(1));
        return "never";
      });

      assertThatThrownBy(scope::join)
          .isInstanceOf(ExecutionException.class)
          .cause()
          .isInstanceOf(CancelledByTimeoutException.class);
      assertThat(scope.isCancelled()).isTrue();
      assertThat(subtask.state()).isEqualTo(Subtask.State.UNAVAILABLE);
    }
  }

  @Test
  void timeoutCountsFromOpenNotFromFirstFork() throws Exception {
    var subtaskRan = new AtomicBoolean();
    try (var scope = StructuredTaskScope.open(cf -> cf.withTimeout(Duration.ofMillis(100)))) {
      Thread.sleep(Duration.ofMillis(300));
      Subtask<String> subtask = scope.fork(() -> {
        subtaskRan.set(true);
        return "too late";
      });

      assertThatThrownBy(scope::join)
          .isInstanceOf(ExecutionException.class)
          .cause()
          .isInstanceOf(CancelledByTimeoutException.class);
      assertThat(subtask.state()).isEqualTo(Subtask.State.UNAVAILABLE);
    }
    assertThat(subtaskRan).isFalse();
  }

  @Test
  void closeWithoutJoinThrowsAndStillWaitsForInterruptedSubtask() {
    var subtaskCompletedCleanupAfterInterrupt = new AtomicBoolean();
    try (var scope = StructuredTaskScope.open()) {
      scope.fork(() -> {
        try {
          Thread.sleep(Duration.ofDays(1));
        } catch (InterruptedException e) {
          Thread.sleep(Duration.ofMillis(50));
          subtaskCompletedCleanupAfterInterrupt.set(true);
        }
        return null;
      });
      assertThatThrownBy(scope::close).isInstanceOf(IllegalStateException.class);
    }
    assertThat(subtaskCompletedCleanupAfterInterrupt).isTrue();
  }

  @Test
  void forkAfterJoinIsRejected() throws Exception {
    try (var scope = StructuredTaskScope.open()) {
      scope.join();

      assertThatThrownBy(() -> scope.fork(() -> "late"))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void onlyOwnerMayForkJoinOrClose() throws Exception {
    try (var scope = StructuredTaskScope.open()) {
      Subtask<List<Throwable>> subtask = scope.fork(() -> List.of(
          catchThrowable(() -> scope.fork(() -> null)),
          catchThrowable(scope::join),
          catchThrowable(scope::close)));
      scope.join();

      assertThat(subtask.get()).hasExactlyElementsOfTypes(
          WrongThreadException.class, WrongThreadException.class, WrongThreadException.class);
    }
  }

  @Test
  void closingOuterScopeBeforeInnerThrowsStructureViolationAndInnerForkNeverRuns() throws Exception {
    try (var outer = StructuredTaskScope.open()) {
      try (var inner = StructuredTaskScope.open()) {
        assertThatThrownBy(outer::close).isInstanceOf(StructureViolationException.class);

        Subtask<String> subtask = inner.fork(() -> "never runs");
        inner.join();
        assertThat(subtask.state()).isEqualTo(Subtask.State.UNAVAILABLE);
      }
    }
  }

  @Test
  void subtaskOutcomeIsHiddenUntilJoin() throws Exception {
    try (var scope = StructuredTaskScope.open()) {
      Subtask<String> subtask = scope.fork(() -> "done");
      awaitState(subtask, Subtask.State.SUCCESS);

      assertThatThrownBy(subtask::get).isInstanceOf(IllegalStateException.class);
      scope.join();
      assertThat(subtask.get()).isEqualTo("done");
    }
  }

  @Test
  void customJoinerSeesEveryForkAndCompletion() throws Exception {
    var forks = new AtomicInteger();
    var completions = new AtomicInteger();
    var joiner = new Joiner<String, Integer, RuntimeException>() {
      @Override
      public boolean onFork(Subtask<String> subtask) {
        forks.incrementAndGet();
        return false;
      }

      @Override
      public boolean onComplete(Subtask<String> subtask) {
        completions.incrementAndGet();
        return false;
      }

      @Override
      public Integer result() {
        return completions.get();
      }

      @Override
      public Integer timeout() {
        return -1;
      }
    };

    try (var scope = StructuredTaskScope.open(joiner)) {
      for (int i = 0; i < 10; i++) {
        scope.fork(() -> "x");
      }

      assertThat(scope.join()).isEqualTo(10);
      assertThat(forks).hasValue(10);
    }
  }

  private static void awaitState(Subtask<?> subtask, Subtask.State state)
      throws InterruptedException {
    while (subtask.state() != state) {
      Thread.sleep(Duration.ofMillis(5));
    }
  }
}
