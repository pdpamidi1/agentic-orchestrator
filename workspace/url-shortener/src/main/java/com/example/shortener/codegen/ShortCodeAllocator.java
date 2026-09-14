package com.example.shortener.codegen;

import com.example.shortener.config.ShortenerProperties;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Allocates short codes: draws a raw value from the primary (Redis) counter, falls back to the
 * database sequence when Redis is unavailable, adds the configured seed offset and base62-encodes
 * the result (AC-10, AC-11, AMB-11).
 *
 * <p>This class owns the fallback decision. Any unchecked exception thrown by the primary source is
 * treated as an outage: it is logged once per failed allocation, by exception type only, so that
 * connection details carried in driver messages never reach the logs, and the fallback source is
 * used for this allocation. The next allocation tries the primary source again, so recovery is
 * automatic. A failure of the fallback itself is propagated to the caller.
 */
@Component
public class ShortCodeAllocator {

  /** Bean name of the primary counter, see {@link RedisBatchCounterSource}. */
  public static final String PRIMARY_SOURCE = "redisBatchCounterSource";

  /** Bean name of the fallback counter, see {@link DbSequenceCounterSource}. */
  public static final String FALLBACK_SOURCE = "dbSequenceCounterSource";

  private static final Logger log = LoggerFactory.getLogger(ShortCodeAllocator.class);

  private final CounterSource primary;
  private final CounterSource fallback;
  private final long seedOffset;

  /** Creates an allocator using the configured {@code shortener.counter-seed-offset}. */
  @Autowired
  public ShortCodeAllocator(
      @Qualifier(PRIMARY_SOURCE) CounterSource primary,
      @Qualifier(FALLBACK_SOURCE) CounterSource fallback,
      ShortenerProperties properties) {
    this(primary, fallback, Objects.requireNonNull(properties, "properties").counterSeedOffset());
  }

  /**
   * Creates an allocator with an explicit seed offset.
   *
   * @param primary the counter tried first (normally Redis)
   * @param fallback the counter used when the primary throws (normally the DB sequence)
   * @param seedOffset value added to every raw counter value before encoding; must be at least
   *     {@link Base62Codec#MIN_OFFSET_FOR_THREE_CHARS} so codes are never shorter than three
   *     characters (AMB-11)
   */
  public ShortCodeAllocator(CounterSource primary, CounterSource fallback, long seedOffset) {
    this.primary = Objects.requireNonNull(primary, "primary");
    this.fallback = Objects.requireNonNull(fallback, "fallback");
    if (seedOffset < Base62Codec.MIN_OFFSET_FOR_THREE_CHARS) {
      throw new IllegalArgumentException(
          "seedOffset must be >= "
              + Base62Codec.MIN_OFFSET_FOR_THREE_CHARS
              + " (62^2) so every code has at least three characters, but was "
              + seedOffset);
    }
    this.seedOffset = seedOffset;
  }

  /** The offset added to raw counter values before encoding. */
  public long seedOffset() {
    return seedOffset;
  }

  /**
   * Allocates the next short code.
   *
   * @return the code and the counter that produced it
   * @throws RuntimeException when both the primary and the fallback counter fail
   */
  public AllocatedCode allocate() {
    CounterSource source = primary;
    long raw;
    try {
      raw = primary.next();
    } catch (RuntimeException primaryFailure) {
      log.warn(
          "Primary short-code counter ({}) unavailable: {}; falling back to {}",
          primary.codeSource().wireValue(),
          primaryFailure.getClass().getSimpleName(),
          fallback.codeSource().wireValue());
      source = fallback;
      raw = fallback.next();
    }
    return new AllocatedCode(encode(raw), source.codeSource());
  }

  private String encode(long raw) {
    if (raw < 0) {
      throw new IllegalStateException("counter source returned a negative value: " + raw);
    }
    return Base62Codec.encode(Math.addExact(raw, seedOffset));
  }
}
