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
 */
@Component
public class RedisBatchCounterSource implements CounterSource {

  /** Global Redis key holding the shared counter. */
  public static final String COUNTER_KEY = "shortener:counter";

  private static final Logger log = LoggerFactory.getLogger(RedisBatchCounterSource.class);

  private final StringRedisTemplate redis;
  private final int batchSize;
  private final Object lock = new Object();

  // Guarded by lock. Invariant: nextValue > rangeEnd means the local range is exhausted.
  private long nextValue = 1;
  private long rangeEnd = 0;

  /** Creates a source using the configured {@code shortener.counter-batch-size}. */
  @Autowired
  public RedisBatchCounterSource(StringRedisTemplate redis, ShortenerProperties properties) {
    this(redis, Objects.requireNonNull(properties, "properties").counterBatchSize());
  }

  /**
   * Creates a source with an explicit batch size.
   *
   * @param redis template used to issue {@code INCRBY}
   * @param batchSize number of values reserved per {@code INCRBY}, {@code >= 1}
   */
  public RedisBatchCounterSource(StringRedisTemplate redis, int batchSize) {
    this.redis = Objects.requireNonNull(redis, "redis");
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be >= 1 but was " + batchSize);
    }
    this.batchSize = batchSize;
  }

  /** Number of values reserved per {@code INCRBY}. */
  public int batchSize() {
    return batchSize;
  }

  @Override
  public long next() {
    synchronized (lock) {
      if (nextValue > rangeEnd) {
        reserveNextRange();
      }
      return nextValue++;
    }
  }

  @Override
  public CodeSource codeSource() {
    return CodeSource.REDIS;
  }

  /**
   * Issues exactly one {@code INCRBY batchSize} and installs the returned range. Holds the lock.
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
    nextValue = start;
    rangeEnd = end;
    log.debug("Reserved counter range [{}, {}] from {}", start, end, COUNTER_KEY);
  }
}
