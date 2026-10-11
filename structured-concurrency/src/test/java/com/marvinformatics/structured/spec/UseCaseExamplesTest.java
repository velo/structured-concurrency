package com.marvinformatics.structured.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.marvinformatics.structured.StructuredTaskScope;
import com.marvinformatics.structured.StructuredTaskScope.CancelledByTimeoutException;
import com.marvinformatics.structured.StructuredTaskScope.Joiner;
import com.marvinformatics.structured.StructuredTaskScope.Subtask;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class UseCaseExamplesTest {

  record User(String id, String name) {}

  record Order(String id, int total) {}

  record Page(User user, List<Order> orders) {}

  static User findUser(String id) throws InterruptedException {
    Thread.sleep(Duration.ofMillis(20));
    return new User(id, "Duke");
  }

  static List<Order> fetchOrders(String userId) throws InterruptedException {
    Thread.sleep(Duration.ofMillis(30));
    return List.of(new Order(userId + "-1", 10), new Order(userId + "-2", 32));
  }

  static String slow(String value, Duration delay) throws InterruptedException {
    Thread.sleep(delay);
    return value;
  }

  @Test
  void fanOutAndCombineHeterogeneousResults() throws Exception {
    Page page;
    try (var scope = StructuredTaskScope.open()) {
      Subtask<User> user = scope.fork(() -> findUser("42"));
      Subtask<List<Order>> orders = scope.fork(() -> fetchOrders("42"));

      scope.join();
      page = new Page(user.get(), orders.get());
    }

    assertThat(page.user().name()).isEqualTo("Duke");
    assertThat(page.orders()).extracting(Order::total).containsExactly(10, 32);
  }

  static <T> List<T> invokeAll(Collection<Callable<T>> tasks) throws InterruptedException {
    try (var scope = StructuredTaskScope.open(
        Joiner.<T, CompletionException>allSuccessfulOrThrow(CompletionException::new))) {
      tasks.forEach(scope::fork);
      return scope.join();
    }
  }

  @Test
  void invokeAllKeepsSubmissionOrder() throws Exception {
    List<Callable<Integer>> tasks = IntStream.rangeClosed(1, 5)
        .<Callable<Integer>>mapToObj(i -> () -> {
          Thread.sleep(Duration.ofMillis(60 - i * 10));
          return i * i;
        })
        .toList();

    assertThat(invokeAll(tasks)).containsExactly(1, 4, 9, 16, 25);
  }

  @Test
  void invokeAllFailsWithTheFirstFailure() {
    List<Callable<Integer>> tasks = List.of(
        () -> 1,
        () -> {
          throw new IllegalStateException("inventory service down");
        });

    assertThatThrownBy(() -> invokeAll(tasks))
        .isInstanceOf(CompletionException.class)
        .rootCause()
        .hasMessage("inventory service down");
  }

  @Test
  void raceReplicasAndTakeTheFastest() throws Exception {
    var losersInterrupted = new CountDownLatch(2);
    Callable<String> replica = () -> {
      try {
        return slow("eu", Duration.ofDays(1));
      } catch (InterruptedException e) {
        losersInterrupted.countDown();
        throw e;
      }
    };

    try (var scope = StructuredTaskScope.open(Joiner.<String>anySuccessfulOrThrow())) {
      scope.fork(replica);
      scope.fork(replica);
      scope.fork(() -> slow("us", Duration.ofMillis(10)));

      assertThat(scope.join()).isEqualTo("us");
    }
    assertThat(losersInterrupted.getCount()).isZero();
  }

  @Test
  void hedgedRequestToleratesAFailingReplica() throws Exception {
    try (var scope = StructuredTaskScope.open(Joiner.<String>anySuccessfulOrThrow())) {
      scope.fork(() -> {
        throw new IllegalStateException("primary failed");
      });
      scope.fork(() -> slow("backup", Duration.ofMillis(20)));

      assertThat(scope.join()).isEqualTo("backup");
    }
  }

  @Test
  void deadlineFallsBackToDefault() throws Exception {
    String recommendation;
    try (var scope = StructuredTaskScope.open(
        Joiner.<String>anySuccessfulOrThrow(), cf -> cf.withTimeout(Duration.ofMillis(50)))) {
      scope.fork(() -> slow("personalized", Duration.ofDays(1)));
      recommendation = scope.join();
    } catch (ExecutionException e) {
      recommendation = switch (e.getCause()) {
        case CancelledByTimeoutException _ -> "bestsellers";
        default -> throw e;
      };
    }

    assertThat(recommendation).isEqualTo("bestsellers");
  }

  @Test
  void scatterGatherReturnsWhateverArrivedBeforeTheDeadline() throws Exception {
    Map<String, Duration> backends = Map.of(
        "web", Duration.ofMillis(5),
        "images", Duration.ofMillis(10),
        "news", Duration.ofDays(1));

    List<String> results;
    try (var scope = StructuredTaskScope.open(
        Joiner.<String>allUntil(_ -> false), cf -> cf.withTimeout(Duration.ofMillis(500)))) {
      backends.forEach((name, latency) -> scope.fork(() -> slow(name, latency)));

      results = scope.join().stream()
          .filter(subtask -> subtask.state() == Subtask.State.SUCCESS)
          .map(Subtask::get)
          .toList();
    }

    assertThat(results).containsExactlyInAnyOrder("web", "images");
  }

  @Test
  void cancelAfterTooManyFailures() throws Exception {
    class CancelAfterTwoFailures implements Predicate<Subtask<?>> {
      private final AtomicInteger failures = new AtomicInteger();

      @Override
      public boolean test(Subtask<?> subtask) {
        return subtask.state() == Subtask.State.FAILED && failures.incrementAndGet() >= 2;
      }
    }

    try (var scope = StructuredTaskScope.open(Joiner.<String>allUntil(new CancelAfterTwoFailures()))) {
      for (int i = 0; i < 3; i++) {
        scope.fork(() -> {
          throw new IllegalStateException("flaky");
        });
      }
      scope.fork(() -> slow("never", Duration.ofDays(1)));

      Map<Subtask.State, Long> byState = scope.join().stream()
          .collect(Collectors.groupingBy(Subtask::state, Collectors.counting()));

      assertThat(scope.isCancelled()).isTrue();
      assertThat(byState.get(Subtask.State.FAILED)).isGreaterThanOrEqualTo(2);
      assertThat(byState).doesNotContainKey(Subtask.State.SUCCESS);
    }
  }

  static final class Quorum<T> implements Joiner<T, List<T>, ExecutionException> {
    private final int required;
    private final Queue<T> results = new ConcurrentLinkedQueue<>();
    private final AtomicInteger successes = new AtomicInteger();

    Quorum(int required) {
      this.required = required;
    }

    @Override
    public boolean onComplete(Subtask<T> subtask) {
      if (subtask.state() != Subtask.State.SUCCESS) {
        return false;
      }
      results.add(subtask.get());
      return successes.incrementAndGet() >= required;
    }

    @Override
    public List<T> result() throws ExecutionException {
      if (successes.get() < required) {
        throw new ExecutionException(new NoSuchElementException("quorum of " + required + " not reached"));
      }
      return results.stream().limit(required).toList();
    }

    @Override
    public List<T> timeout() throws ExecutionException {
      throw new ExecutionException(new CancelledByTimeoutException());
    }
  }

  @Test
  void customJoinerWaitsForQuorum() throws Exception {
    try (var scope = StructuredTaskScope.open(new Quorum<String>(2))) {
      scope.fork(() -> slow("a", Duration.ofMillis(5)));
      scope.fork(() -> slow("b", Duration.ofMillis(10)));
      scope.fork(() -> slow("c", Duration.ofDays(1)));
      scope.fork(() -> {
        throw new IllegalStateException("replica d down");
      });

      assertThat(scope.join()).containsExactlyInAnyOrder("a", "b");
      assertThat(scope.isCancelled()).isTrue();
    }
  }

  static final class CollectAllFailures implements Joiner<Object, Void, IllegalStateException> {
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
        var aggregate = new IllegalStateException(failures.size() + " validations failed");
        failures.forEach(aggregate::addSuppressed);
        throw aggregate;
      }
      return null;
    }

    @Override
    public Void timeout() {
      throw new IllegalStateException(new CancelledByTimeoutException());
    }
  }

  @Test
  void customJoinerReportsEveryFailure() {
    assertThatThrownBy(() -> {
      try (var scope = StructuredTaskScope.open(new CollectAllFailures())) {
        scope.fork(() -> {
          throw new IllegalArgumentException("email is invalid");
        });
        scope.fork(() -> "address ok");
        scope.fork(() -> {
          throw new IllegalArgumentException("age is negative");
        });
        scope.join();
      }
    })
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("2 validations failed")
        .satisfies(e -> assertThat(e.getSuppressed())
            .extracting(Throwable::getMessage)
            .containsExactlyInAnyOrder("email is invalid", "age is negative"));
  }

  @Test
  void nestedScopesFormATaskTree() throws Exception {
    try (var scope = StructuredTaskScope.open()) {
      Subtask<User> user = scope.fork(() -> findUser("7"));
      Subtask<Integer> spent = scope.fork(() -> {
        try (var inner = StructuredTaskScope.open(Joiner.<List<Order>>allSuccessfulOrThrow())) {
          inner.fork(() -> fetchOrders("7-web"));
          inner.fork(() -> fetchOrders("7-store"));
          return inner.join().stream().flatMap(List::stream).mapToInt(Order::total).sum();
        }
      });

      scope.join();
      assertThat(user.get().id()).isEqualTo("7");
      assertThat(spent.get()).isEqualTo(84);
    }
  }

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

  @Test
  void divideAndConquerWithRecursiveScopes() throws Exception {
    long[] values = IntStream.rangeClosed(1, 100_000).asLongStream().toArray();

    assertThat(parallelSum(values, 0, values.length)).isEqualTo(5_000_050_000L);
  }

  @Test
  void boundedConcurrencyWithSemaphore() throws Exception {
    var permits = new Semaphore(3);
    var running = new AtomicInteger();
    var peak = new AtomicInteger();

    try (var scope = StructuredTaskScope.open(Joiner.<Integer>allSuccessfulOrThrow())) {
      for (int i = 0; i < 20; i++) {
        int id = i;
        scope.fork(() -> {
          permits.acquire();
          try {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            Thread.sleep(Duration.ofMillis(10));
            return id;
          } finally {
            running.decrementAndGet();
            permits.release();
          }
        });
      }

      assertThat(scope.join()).hasSize(20);
    }
    assertThat(peak.get()).isBetween(1, 3);
  }

  static final class PaymentDeclined extends RuntimeException {
    PaymentDeclined(Throwable cause) {
      super("payment declined", cause);
    }
  }

  @Test
  void translateFailuresIntoDomainException() {
    assertThatThrownBy(() -> {
      try (var scope = StructuredTaskScope.open(
          Joiner.<Object, PaymentDeclined>awaitAllSuccessfulOrThrow(PaymentDeclined::new))) {
        scope.fork(() -> "fraud check passed");
        scope.fork(() -> {
          throw new IllegalStateException("insufficient funds");
        });
        scope.join();
      }
    })
        .isInstanceOf(PaymentDeclined.class)
        .cause()
        .hasMessage("insufficient funds");
  }

  @Test
  void interruptingTheOwnerCancelsTheWholeTree() throws Exception {
    var started = new CountDownLatch(2);
    var cancelled = new CountDownLatch(2);
    var outcome = new ConcurrentLinkedQueue<Class<?>>();

    Thread owner = Thread.ofVirtual().start(() -> {
      try (var scope = StructuredTaskScope.open()) {
        for (int i = 0; i < 2; i++) {
          scope.fork(() -> {
            started.countDown();
            try {
              Thread.sleep(Duration.ofDays(1));
            } catch (InterruptedException e) {
              cancelled.countDown();
            }
          });
        }
        scope.join();
      } catch (Exception e) {
        outcome.add(e.getClass());
      }
    });

    started.await();
    owner.interrupt();
    owner.join();

    assertThat(outcome).containsExactly(InterruptedException.class);
    assertThat(cancelled.getCount()).isZero();
  }

  @Test
  void optionalEnrichmentDoesNotFailTheRequest() throws Exception {
    try (var scope = StructuredTaskScope.open(Joiner.<Object>allUntil(_ -> false))) {
      Subtask<User> user = scope.fork(() -> findUser("1"));
      Subtask<String> avatar = scope.fork(() -> {
        throw new IllegalStateException("avatar service down");
      });
      scope.join();

      Optional<String> avatarUrl = avatar.state() == Subtask.State.SUCCESS
          ? Optional.of(avatar.get())
          : Optional.empty();

      assertThat(user.get().name()).isEqualTo("Duke");
      assertThat(avatarUrl).isEmpty();
    }
  }
}
