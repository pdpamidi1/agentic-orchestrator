/*
 * RetryExecutorTest.java — bounded attempts, backoff sequence, jitter, timeout and exhaustion
 *
 * Layer: test (unit). Drives RetryExecutor with a recording sleeper and a seeded random generator:
 * the NONE schedule is exactly base * 2^n capped; FULL and EQUAL stay inside their bounds and are
 * reproducible for the same seed; a hung attempt is cut off at the per-attempt timeout and the
 * future cancelled; success stops the loop; exhaustion throws with the attempt count and last
 * cause; an interrupt aborts without reporting exhaustion (AC-4, AC-5). No Spring context.
 */
package com.example.shortener.analytics.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.shortener.analytics.config.AnalyticsProperties;
import com.example.shortener.analytics.config.AnalyticsProperties.Jitter;
import com.example.shortener.analytics.config.AnalyticsProperties.Retry;
import com.example.shortener.analytics.support.RetryExecutor;
import com.example.shortener.analytics.support.RetryExecutor.RetryExhaustedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Unit tests of {@link RetryExecutor}. */
class RetryExecutorTest {

  private static final long SEED = 42L;
  private static final Duration TIMEOUT = Duration.ofMillis(50);

  private final List<Duration> sleeps = new ArrayList<>();
  private final List<Integer> retriedAttempts = new ArrayList<>();
  private final List<Throwable> causes = new ArrayList<>();

  private RetryExecutor executor(int maxAttempts, Jitter jitter, long seed) {
    return new RetryExecutor(
        new Retry(maxAttempts, 100, 10_000, jitter),
        TIMEOUT,
        new SplittableRandom(seed),
        sleeps::add);
  }

  private RetryExecutor.RetryListener listener() {
    return (attempt, backoff, cause) -> {
      retriedAttempts.add(attempt);
      causes.add(cause);
    };
  }

  /** Without jitter the sequence is 100, 200, 400, 800 ms and no sleep follows the last failure. */
  @Test
  void noneJitterProducesExactExponentialSequence() {
    RetryExecutor executor = executor(5, Jitter.NONE, SEED);

    assertThatThrownBy(
            () ->
                executor.execute(
                    () -> CompletableFuture.failedFuture(new IllegalStateException("down")),
                    listener()))
        .isInstanceOf(RetryExhaustedException.class);

    assertThat(sleeps)
        .containsExactly(
            Duration.ofMillis(100),
            Duration.ofMillis(200),
            Duration.ofMillis(400),
            Duration.ofMillis(800));
    assertThat(retriedAttempts).containsExactly(1, 2, 3, 4);
    assertThat(causes).allSatisfy(c -> assertThat(c).hasMessage("down"));
  }

  /** The deterministic delay is capped at max-backoff-ms and never overflows. */
  @Test
  void deterministicBackoffIsCapped() {
    RetryExecutor executor =
        new RetryExecutor(
            new Retry(5, 100, 350, Jitter.NONE), TIMEOUT, new SplittableRandom(SEED), sleeps::add);

    assertThat(executor.backoffAfter(1)).isEqualTo(Duration.ofMillis(100));
    assertThat(executor.backoffAfter(2)).isEqualTo(Duration.ofMillis(200));
    assertThat(executor.backoffAfter(3)).isEqualTo(Duration.ofMillis(350));
    assertThat(executor.backoffAfter(40)).isEqualTo(Duration.ofMillis(350));
    assertThat(executor.backoffAfter(100)).isEqualTo(Duration.ofMillis(350));
    assertThatThrownBy(() -> executor.backoffAfter(0)).isInstanceOf(IllegalArgumentException.class);
  }

  /** Full jitter stays within [0, cap_n] and is reproducible for the same seed. */
  @Test
  void fullJitterIsBoundedAndDeterministicForASeed() {
    RetryExecutor first = executor(5, Jitter.FULL, SEED);
    RetryExecutor second = executor(5, Jitter.FULL, SEED);

    List<Duration> firstRun = new ArrayList<>();
    List<Duration> secondRun = new ArrayList<>();
    for (int n = 1; n <= 4; n++) {
      firstRun.add(first.backoffAfter(n));
      secondRun.add(second.backoffAfter(n));
    }

    assertThat(firstRun).isEqualTo(secondRun);
    for (int n = 1; n <= 4; n++) {
      long ceiling = 100L << (n - 1);
      assertThat(firstRun.get(n - 1).toMillis()).isBetween(0L, ceiling);
    }
    // A different seed produces a different sequence (the jitter really is random).
    List<Duration> otherSeed = new ArrayList<>();
    RetryExecutor third = executor(5, Jitter.FULL, SEED + 1);
    for (int n = 1; n <= 4; n++) {
      otherSeed.add(third.backoffAfter(n));
    }
    assertThat(otherSeed).isNotEqualTo(firstRun);
  }

  /** Equal jitter stays within [cap_n / 2, cap_n]. */
  @Test
  void equalJitterKeepsHalfOfTheDelay() {
    RetryExecutor executor = executor(5, Jitter.EQUAL, SEED);
    for (int n = 1; n <= 6; n++) {
      long ceiling = Math.min(10_000L, 100L << (n - 1));
      assertThat(executor.backoffAfter(n).toMillis()).isBetween(ceiling / 2, ceiling);
    }
  }

  /** The executor sleeps exactly the jittered value it computed, in order. */
  @Test
  void executeSleepsTheJitteredBackoffs() {
    RetryExecutor expected = executor(4, Jitter.FULL, SEED);
    List<Duration> expectedSleeps =
        List.of(expected.backoffAfter(1), expected.backoffAfter(2), expected.backoffAfter(3));
    RetryExecutor executor = executor(4, Jitter.FULL, SEED);

    assertThatThrownBy(
            () ->
                executor.execute(
                    () -> CompletableFuture.failedFuture(new RuntimeException("x")), listener()))
        .isInstanceOf(RetryExhaustedException.class);

    assertThat(sleeps).isEqualTo(expectedSleeps);
  }

  /** A success stops the loop immediately and returns the value. */
  @Test
  void returnsFirstSuccessfulResult() {
    RetryExecutor executor = executor(5, Jitter.NONE, SEED);
    AtomicInteger calls = new AtomicInteger();

    String result =
        executor.execute(
            () ->
                calls.incrementAndGet() < 3
                    ? CompletableFuture.failedFuture(new RuntimeException("not yet"))
                    : CompletableFuture.completedFuture("ok"),
            listener());

    assertThat(result).isEqualTo("ok");
    assertThat(calls).hasValue(3);
    assertThat(sleeps).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200));
    assertThat(retriedAttempts).containsExactly(1, 2);
  }

  /** A hung attempt is cut off at the per-attempt timeout, cancelled and counted as a failure. */
  @Test
  void hungAttemptTimesOutAndIsCancelled() {
    RetryExecutor executor = executor(2, Jitter.NONE, SEED);
    List<CompletableFuture<String>> futures = new ArrayList<>();

    assertThatThrownBy(
            () ->
                executor.execute(
                    () -> {
                      CompletableFuture<String> hung = new CompletableFuture<>();
                      futures.add(hung);
                      return hung;
                    },
                    listener()))
        .isInstanceOf(RetryExhaustedException.class)
        .hasCauseInstanceOf(TimeoutException.class)
        .satisfies(e -> assertThat(((RetryExhaustedException) e).getAttempts()).isEqualTo(2));

    assertThat(futures).hasSize(2).allSatisfy(f -> assertThat(f.isCancelled()).isTrue());
    assertThat(causes).hasSize(1).first().isInstanceOf(TimeoutException.class);
    assertThat(sleeps).containsExactly(Duration.ofMillis(100));
    assertThat(executor.attemptTimeout()).isEqualTo(TIMEOUT);
  }

  /** A supplier that throws synchronously is a failed attempt, not a crash. */
  @Test
  void synchronousExceptionCountsAsFailedAttempt() {
    RetryExecutor executor = executor(3, Jitter.NONE, SEED);
    AtomicInteger calls = new AtomicInteger();

    assertThatThrownBy(
            () ->
                executor.execute(
                    () -> {
                      calls.incrementAndGet();
                      throw new IllegalArgumentException("boom");
                    },
                    listener()))
        .isInstanceOf(RetryExhaustedException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("3 attempt(s)");

    assertThat(calls).hasValue(3);
    assertThat(executor.maxAttempts()).isEqualTo(3);
  }

  /** One attempt only: no sleep, no listener call, exhaustion after the single failure. */
  @Test
  void singleAttemptPolicyNeverSleeps() {
    RetryExecutor executor = executor(1, Jitter.FULL, SEED);

    assertThatThrownBy(
            () ->
                executor.execute(
                    () -> CompletableFuture.failedFuture(new RuntimeException("x")), listener()))
        .isInstanceOf(RetryExhaustedException.class);

    assertThat(sleeps).isEmpty();
    assertThat(retriedAttempts).isEmpty();
  }

  /** An interrupt during backoff aborts with the flag restored and is not exhaustion. */
  @Test
  void interruptDuringBackoffAbortsWithoutExhaustion() {
    RetryExecutor executor =
        new RetryExecutor(
            new Retry(5, 100, 10_000, Jitter.NONE),
            TIMEOUT,
            new SplittableRandom(SEED),
            duration -> {
              throw new InterruptedException("stop");
            });

    try {
      assertThatThrownBy(
              () ->
                  executor.execute(
                      () -> CompletableFuture.failedFuture(new RuntimeException("x")), listener()))
          .isInstanceOf(IllegalStateException.class)
          .isNotInstanceOf(RetryExhaustedException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  /** The Spring constructor takes policy and timeout from the bound properties. */
  @Test
  void springConstructorReadsPolicyFromProperties() {
    AnalyticsProperties properties =
        new AnalyticsProperties(
            "unit-test-salt-0123456789",
            new AnalyticsProperties.Outbox(500, 100),
            new Retry(7, 100, 10_000, Jitter.FULL),
            new AnalyticsProperties.Kafka(1234),
            new AnalyticsProperties.Retention(90));

    RetryExecutor executor = new RetryExecutor(properties);

    assertThat(executor.maxAttempts()).isEqualTo(7);
    assertThat(executor.attemptTimeout()).isEqualTo(Duration.ofMillis(1234));
  }

  /** Every collaborator is mandatory and the timeout must be positive. */
  @Test
  void invalidArgumentsAreRejected() {
    Retry retry = new Retry(5, 100, 10_000, Jitter.FULL);
    SplittableRandom random = new SplittableRandom(SEED);
    assertThatThrownBy(() -> new RetryExecutor(null, TIMEOUT, random, sleeps::add))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RetryExecutor(retry, Duration.ZERO, random, sleeps::add))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RetryExecutor(retry, TIMEOUT, null, sleeps::add))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RetryExecutor(retry, TIMEOUT, random, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RetryExecutor((AnalyticsProperties) null))
        .isInstanceOf(NullPointerException.class);
  }
}
