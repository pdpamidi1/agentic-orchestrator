/*
 * RetryExecutor.java — bounded retries with exponential backoff, jitter and a per-attempt timeout
 *
 * Layer: analytics.support (depends on analytics.config only; imports no Kafka type). Used by the
 * outbox relay (analytics.outbox.OutboxPublishService) to drive one publish through the AMB-17
 * policy: analytics.retry.max-attempts attempts, backoff min(cap, base * 2^n) with none / equal /
 * full jitter, and analytics.kafka.timeout-ms as the upper bound of a single attempt (AC-4, AC-5).
 */
package com.example.shortener.analytics.support;

import com.example.shortener.analytics.config.AnalyticsProperties;
import java.time.Duration;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Runs an asynchronous operation with bounded attempts, exponential backoff plus jitter and a
 * per-attempt timeout (AMB-17).
 *
 * <p><b>Responsibility.</b> {@link #execute} starts the supplied operation up to {@code
 * maxAttempts} times. An attempt fails when its future completes exceptionally, when the supplier
 * throws, or when the future has not completed within {@code attemptTimeout} (the future is then
 * cancelled). Between two attempts the executor notifies the listener, then sleeps for {@link
 * #backoffAfter}. When the last attempt fails a {@link RetryExhaustedException} carrying the
 * attempt count and the last cause is thrown; the caller decides what "exhausted" means (the relay
 * parks the row as {@code FAILED}).
 *
 * <p><b>Backoff.</b> After the {@code n}-th failed attempt ({@code n >= 1}) the deterministic delay
 * is {@code d = min(maxBackoff, baseBackoff * 2^(n-1))}. {@code NONE} sleeps {@code d}, {@code
 * EQUAL} sleeps {@code d/2 + random[0, d/2]}, {@code FULL} sleeps {@code random[0, d]}. The random
 * source is injectable so tests can pin the sequence with a seed.
 *
 * <p><b>Interruption.</b> An interrupt while waiting or sleeping restores the interrupt flag and
 * aborts with an {@link IllegalStateException}; it is <em>not</em> reported as exhaustion, so a
 * shutdown never parks rows.
 *
 * <p><b>Thread-safety and lifecycle.</b> Immutable singleton bean apart from the random generator,
 * which is only touched by the calling thread of {@link #execute}; the relay runs on a single
 * scheduler thread. The Spring constructor uses the system sleeper and an unseeded generator.
 */
@Component
public class RetryExecutor {

  /** Sleeps between attempts; {@link Thread#sleep(long)} live, a recorder in tests. */
  @FunctionalInterface
  public interface Sleeper {
    /**
     * Blocks for the given duration.
     *
     * @param duration how long to sleep; never negative
     * @throws InterruptedException when the thread is interrupted while sleeping
     */
    void sleep(Duration duration) throws InterruptedException;
  }

  /** Observes failed attempts that will be retried (not the final one). */
  @FunctionalInterface
  public interface RetryListener {
    /**
     * Called after a failed attempt when another attempt follows, before the backoff sleep.
     *
     * @param failedAttempt 1-based index of the attempt that just failed
     * @param backoff the delay that will be slept before the next attempt
     * @param cause why the attempt failed (execution failure, supplier exception or timeout)
     */
    void onRetry(int failedAttempt, Duration backoff, Throwable cause);
  }

  /** Thrown by {@link #execute} when every attempt failed. */
  public static class RetryExhaustedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Number of attempts that were made. */
    private final int attempts;

    /**
     * Creates the exception.
     *
     * @param attempts number of attempts made
     * @param lastCause failure of the last attempt
     */
    public RetryExhaustedException(int attempts, Throwable lastCause) {
      super("publish failed after " + attempts + " attempt(s)", lastCause);
      this.attempts = attempts;
    }

    /** Number of attempts that were made before giving up. */
    public int getAttempts() {
      return attempts;
    }
  }

  /** Attempt budget and backoff policy. */
  private final AnalyticsProperties.Retry policy;

  /** Upper bound for one attempt. */
  private final Duration attemptTimeout;

  /** Source of jitter. */
  private final RandomGenerator random;

  /** Sleeps between attempts. */
  private final Sleeper sleeper;

  /**
   * Creates the executor Spring uses: policy and timeout from the bound properties, an unseeded
   * random generator and a real sleeper.
   *
   * @param properties the analytics settings
   * @throws NullPointerException when {@code properties} is {@code null}
   */
  @Autowired
  public RetryExecutor(AnalyticsProperties properties) {
    this(
        Objects.requireNonNull(properties, "properties").retry(),
        properties.kafka().timeout(),
        new SplittableRandom(),
        duration -> Thread.sleep(duration.toMillis()));
  }

  /**
   * Creates the executor with every collaborator explicit (tests).
   *
   * @param policy attempt budget and backoff policy
   * @param attemptTimeout upper bound for one attempt; must be positive
   * @param random source of jitter; a seeded generator gives a reproducible sequence
   * @param sleeper sleeps between attempts
   * @throws NullPointerException when any argument is {@code null}
   * @throws IllegalArgumentException when {@code attemptTimeout} is zero or negative
   */
  public RetryExecutor(
      AnalyticsProperties.Retry policy,
      Duration attemptTimeout,
      RandomGenerator random,
      Sleeper sleeper) {
    this.policy = Objects.requireNonNull(policy, "policy");
    this.attemptTimeout = Objects.requireNonNull(attemptTimeout, "attemptTimeout");
    if (attemptTimeout.isZero() || attemptTimeout.isNegative()) {
      throw new IllegalArgumentException("attemptTimeout must be positive");
    }
    this.random = Objects.requireNonNull(random, "random");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
  }

  /** Maximum number of attempts, including the first. */
  public int maxAttempts() {
    return policy.maxAttempts();
  }

  /** Upper bound of a single attempt. */
  public Duration attemptTimeout() {
    return attemptTimeout;
  }

  /**
   * Runs {@code attempt} until it succeeds or the attempt budget is exhausted.
   *
   * @param attempt starts one asynchronous attempt; called once per attempt
   * @param listener notified before each backoff sleep
   * @param <T> result type
   * @return the value of the first successful attempt (may be {@code null})
   * @throws RetryExhaustedException when all {@code maxAttempts} attempts failed
   * @throws IllegalStateException when the calling thread was interrupted (flag restored)
   * @throws NullPointerException when an argument is {@code null}
   */
  public <T> T execute(Supplier<CompletableFuture<T>> attempt, RetryListener listener) {
    Objects.requireNonNull(attempt, "attempt");
    Objects.requireNonNull(listener, "listener");
    int max = policy.maxAttempts();
    Throwable last = null;
    for (int n = 1; n <= max; n++) {
      CompletableFuture<T> future = null;
      try {
        future = Objects.requireNonNull(attempt.get(), "attempt returned null future");
        return future.get(attemptTimeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        cancel(future);
        throw new IllegalStateException("interrupted during attempt " + n, e);
      } catch (TimeoutException e) {
        cancel(future);
        last = new TimeoutException("attempt " + n + " exceeded " + attemptTimeout);
      } catch (ExecutionException e) {
        last = e.getCause() != null ? e.getCause() : e;
      } catch (RuntimeException e) {
        last = e;
      }
      if (n < max) {
        Duration backoff = backoffAfter(n);
        listener.onRetry(n, backoff, last);
        try {
          sleeper.sleep(backoff);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("interrupted during backoff after attempt " + n, e);
        }
      }
    }
    throw new RetryExhaustedException(max, last);
  }

  /**
   * Delay to sleep after the {@code failedAttempt}-th failure, jitter applied.
   *
   * @param failedAttempt 1-based index of the failed attempt
   * @return a duration in {@code [0, maxBackoff]}
   * @throws IllegalArgumentException when {@code failedAttempt < 1}
   */
  public Duration backoffAfter(int failedAttempt) {
    long ceiling = deterministicBackoffMs(failedAttempt);
    long sleep =
        switch (policy.jitter()) {
          case NONE -> ceiling;
          case EQUAL -> ceiling / 2 + randomInclusive(ceiling - ceiling / 2);
          case FULL -> randomInclusive(ceiling);
        };
    return Duration.ofMillis(sleep);
  }

  /** {@code min(cap, base * 2^(n-1))} without overflow. */
  private long deterministicBackoffMs(int failedAttempt) {
    if (failedAttempt < 1) {
      throw new IllegalArgumentException("failedAttempt must be >= 1");
    }
    long base = policy.baseBackoffMs();
    long cap = policy.maxBackoffMs();
    int exponent = failedAttempt - 1;
    if (exponent >= 62 || base > (cap >> Math.min(exponent, 62))) {
      return cap;
    }
    return Math.min(cap, base << exponent);
  }

  /** Uniform value in {@code [0, bound]}. */
  private long randomInclusive(long bound) {
    return bound <= 0 ? 0 : random.nextLong(bound + 1);
  }

  private static void cancel(CompletableFuture<?> future) {
    if (future != null) {
      future.cancel(true);
    }
  }
}
