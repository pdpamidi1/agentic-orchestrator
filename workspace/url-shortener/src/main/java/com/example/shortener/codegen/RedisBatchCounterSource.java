/*
 * RedisBatchCounterSource.java — Primary CounterSource: batched Redis INCRBY on shortener:counter
 *
 * Layer: codegen. The write-side Redis boundary class (the read-side one is RedisUrlCache) and
 * the only class besides it allowed to reference Spring Data Redis (ArchitectureTest). Reserves
 * counter values in batches so that steady-state allocation needs no network call, and reports
 * Redis failures by throwing so that ShortCodeAllocator can fall back to DbSequenceCounterSource.
 * Serves AC-10 / ADR-002 and the batch-size and counter-gap behaviour in docs/operations.md 2.
 */
package com.example.shortener.codegen;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.CodeSource;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Primary counter: reserves a batch of values with a single {@code INCRBY <batchSize>} on the
 * global Redis key {@value #COUNTER_KEY} and hands them out locally (AC-10, ADR-002).
 *
 * <p>Each instance owns the range {@code [reply - batchSize + 1, reply]} returned by its last
 * {@code INCRBY}. Values are handed out under a lock, so within one instance they are strictly
 * increasing and unique; across instances uniqueness follows from Redis' atomic increment. A range
 * that is still partly unused when the process stops is simply lost: restart gaps are accepted
 * (AMB-10).
 *
 * <p>This is the only class besides the URL cache that may reference Spring Data Redis types. Redis
 * failures (connection refused, timeout, ...) are propagated as unchecked exceptions; the fallback
 * decision is taken by {@link ShortCodeAllocator}.
 *
 * <p><b>Invariants</b> (all guarded by {@code lock}):
 *
 * <ul>
 *   <li>{@code nextValue > rangeEnd} means the local range is exhausted and the next call must
 *       reserve; initially {@code nextValue = 1, rangeEnd = 0} so the very first call reserves.
 *   <li>{@code rangeEnd} never decreases. A reservation whose start is not above the previous
 *       {@code rangeEnd} is refused, because the Redis key must have moved backwards (flush,
 *       restore of an old dump) and re-issuing values could collide with persisted codes.
 *   <li>Every value returned is {@code >= 1}; a reply that would place the range below 1 (key set
 *       to a negative number) is refused as well.
 * </ul>
 *
 * <p><b>Thread-safety.</b> All mutable state is read and written inside {@code synchronized
 * (lock)}, including the Redis call, so concurrent allocations see one {@code INCRBY} per batch and
 * never share a value. The lock is held during the network round trip, which serialises allocation
 * on batch boundaries only.
 *
 * <p><b>Lifecycle.</b> Singleton {@code @Component} registered under the bean name {@code
 * redisBatchCounterSource} ({@link ShortCodeAllocator#PRIMARY_SOURCE}). It performs no Redis I/O at
 * construction; the first {@code INCRBY} happens lazily on the first {@link #next()}, which is why
 * {@code read}-profile instances, which never allocate, never touch the counter key.
 *
 * <p><b>Design choice.</b> A shared counter with batching gives global uniqueness without
 * coordination on every request: one round trip per {@code shortener.counter-batch-size} codes per
 * instance. The price is the gap of up to {@code batchSize - 1} values per restart and the
 * requirement that the key live in a persistent, non-evicting Redis.
 */
@Component
public class RedisBatchCounterSource implements CounterSource {

  /**
   * Global Redis key holding the shared counter. A plain integer string incremented with {@code
   * INCRBY}; it starts implicitly at 0 (first reply equals {@code batchSize}). Must never be reset
   * or lowered, only ever moved forward (operations 2.3).
   */
  public static final String COUNTER_KEY = "shortener:counter";

  /** Logs at DEBUG only: one line per reserved range, none per allocation. */
  private static final Logger log = LoggerFactory.getLogger(RedisBatchCounterSource.class);

  /** Template used for the single {@code INCRBY}; the only Redis dependency of this class. */
  private final StringRedisTemplate redis;

  /**
   * Values reserved per {@code INCRBY}, {@code >= 1}. Default 1000 from {@code
   * shortener.counter-batch-size}: one round trip per thousand codes while bounding the restart gap
   * at 999. Integration tests use 1 so every allocation is observable in Redis.
   */
  private final int batchSize;

  /** Monitor guarding {@link #nextValue} and {@link #rangeEnd}; a private object, never leaked. */
  private final Object lock = new Object();

  // Guarded by lock. Invariant: nextValue > rangeEnd means the local range is exhausted.
  /** Next value to hand out from the current local range. */
  private long nextValue = 1;

  /** Last value (inclusive) of the current local range; 0 until the first reservation. */
  private long rangeEnd = 0;

  /**
   * Creates a source using the configured {@code shortener.counter-batch-size}. This is the
   * constructor Spring uses.
   *
   * @param redis template used to issue {@code INCRBY}
   * @param properties bound {@code shortener.*} settings; only {@code counterBatchSize()} is read
   * @throws NullPointerException when {@code redis} or {@code properties} is {@code null}
   */
  @Autowired
  public RedisBatchCounterSource(StringRedisTemplate redis, ShortenerProperties properties) {
    this(redis, Objects.requireNonNull(properties, "properties").counterBatchSize());
  }

  /**
   * Creates a source with an explicit batch size.
   *
   * @param redis template used to issue {@code INCRBY}
   * @param batchSize number of values reserved per {@code INCRBY}, {@code >= 1}
   * @throws NullPointerException when {@code redis} is {@code null}
   * @throws IllegalArgumentException when {@code batchSize < 1}
   */
  public RedisBatchCounterSource(StringRedisTemplate redis, int batchSize) {
    this.redis = Objects.requireNonNull(redis, "redis");
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be >= 1 but was " + batchSize);
    }
    this.batchSize = batchSize;
  }

  /**
   * Number of values reserved per {@code INCRBY}.
   *
   * @return the batch size, {@code >= 1}
   */
  public int batchSize() {
    return batchSize;
  }

  /**
   * Hands out the next value of the local range, reserving a new range from Redis first when the
   * current one is exhausted.
   *
   * <p>Side effects: at most one {@code INCRBY shortener:counter <batchSize>} per {@code batchSize}
   * calls. If the reservation throws, no local state has changed, so the next call simply retries
   * the {@code INCRBY}; this is what makes recovery after an outage automatic.
   *
   * @return a value {@code >= 1}, strictly greater than every value this instance returned before
   * @throws org.springframework.data.redis.RedisConnectionFailureException or another Spring Data
   *     Redis {@code DataAccessException} when Redis is unreachable or times out
   * @throws IllegalStateException when Redis returns no value, or a range below 1, or a range that
   *     would re-issue values already handed out ("moved backwards")
   */
  @Override
  public long next() {
    synchronized (lock) {
      if (nextValue > rangeEnd) {
        reserveNextRange();
      }
      return nextValue++;
    }
  }

  /**
   * Always {@link CodeSource#REDIS}.
   *
   * @return the wire-level origin {@code "redis"}
   */
  @Override
  public CodeSource codeSource() {
    return CodeSource.REDIS;
  }

  /**
   * Issues exactly one {@code INCRBY batchSize} and installs the returned range. Holds the lock.
   *
   * <p>The reply is the new counter value, i.e. the <em>end</em> of the reserved range; the start
   * is {@code reply - batchSize + 1}. Sanity checks refuse a missing reply (transaction/pipeline
   * misuse in the template), a start below 1 (key holding a negative or garbage value), and a start
   * at or below the previous {@code rangeEnd} (key moved backwards). On any exception the fields
   * are left untouched, so a failed reservation never corrupts the local range.
   *
   * @throws IllegalStateException for the three refusal cases above
   * @throws RuntimeException whatever the Redis template throws on a failed round trip
   */
  private void reserveNextRange() {
    Long reply = redis.opsForValue().increment(COUNTER_KEY, batchSize);
    if (reply == null) {
      throw new IllegalStateException("Redis INCRBY returned no value for key " + COUNTER_KEY);
    }
    long end = reply;
    long start = end - batchSize + 1;
    if (start < 1) {
      throw new IllegalStateException(
          "Redis counter "
              + COUNTER_KEY
              + " is below the valid range (INCRBY returned "
              + end
              + ")");
    }
    if (start <= rangeEnd) {
      // The key went backwards (reset/flush). Reusing values would collide with persisted codes.
      throw new IllegalStateException(
          "Redis counter "
              + COUNTER_KEY
              + " moved backwards: new range starts at "
              + start
              + " but "
              + rangeEnd
              + " was already handed out");
    }
    // Only now, after all checks passed, does the local range change.
    nextValue = start;
    rangeEnd = end;
    log.debug("Reserved counter range [{}, {}] from {}", start, end, COUNTER_KEY);
  }
}
