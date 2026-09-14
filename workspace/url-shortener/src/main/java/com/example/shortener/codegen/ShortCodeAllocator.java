/*
 * ShortCodeAllocator.java — Allocates short codes: primary counter, fallback, seed offset, base62
 *
 * Layer: codegen. The single entry point the write path (UrlWriteService) uses to obtain a
 * generated short code. Composes the two CounterSource beans, owns the Redis-outage fallback
 * decision (ArchitectureTest: "RedisBatchCounterSource throws and ShortCodeAllocator decides"),
 * applies shortener.counter-seed-offset and delegates encoding to Base62Codec. Serves AC-10,
 * AC-11, AMB-11 and the WARN line documented in docs/operations.md 1.3.
 */
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
 *
 * <p><b>Invariants.</b> {@code seedOffset >= 62^2}, so every produced code has at least three
 * characters; with the default {@code 62^5} every code has at least six. A code is always {@code
 * Base62Codec.encode(raw + seedOffset)} regardless of which source supplied {@code raw}, so codes
 * from both counters have the same shape and the {@code CodeSource} in the result is the only trace
 * of their origin. Negative raw values are refused rather than encoded.
 *
 * <p><b>Thread-safety and lifecycle.</b> Immutable after construction (two final references and a
 * final long); concurrency is delegated to the sources. Singleton {@code @Component} wired with the
 * two qualified {@link CounterSource} beans; there is no circuit breaker or cached health state, by
 * design (operations 1.2: "every request first tries Redis again").
 *
 * <p><b>Design choice.</b> Keeping offset and encoding here, outside the sources, means the two
 * counters need only agree on producing distinct non-negative integers. The seed offset is
 * configuration rather than a constant so operators can lengthen codes without a migration; it
 * cannot be lowered below the three-character floor.
 */
@Component
public class ShortCodeAllocator {

  /**
   * Bean name of the primary counter, see {@link RedisBatchCounterSource}. Spring derives it from
   * the class name; the constant makes the {@code @Qualifier} wiring explicit and testable.
   */
  public static final String PRIMARY_SOURCE = "redisBatchCounterSource";

  /** Bean name of the fallback counter, see {@link DbSequenceCounterSource}. */
  public static final String FALLBACK_SOURCE = "dbSequenceCounterSource";

  /** Emits exactly one WARN per failed primary allocation, by exception class name only. */
  private static final Logger log = LoggerFactory.getLogger(ShortCodeAllocator.class);

  /** Tried first on every allocation; normally the Redis batch counter. */
  private final CounterSource primary;

  /** Used only when {@link #primary} throws; normally the Postgres sequence. */
  private final CounterSource fallback;

  /**
   * Added to every raw counter value before encoding, {@code >= 62^2}. Default {@code 62^5 =
   * 916_132_832} from {@code shortener.counter-seed-offset}: the smallest value whose base62 form
   * has six characters, so the first generated code is {@code "100000"} rather than {@code "1"}.
   */
  private final long seedOffset;

  /**
   * Creates an allocator using the configured {@code shortener.counter-seed-offset}. This is the
   * constructor Spring uses; the qualifiers pin the roles of the two {@link CounterSource} beans.
   *
   * @param primary the {@code redisBatchCounterSource} bean
   * @param fallback the {@code dbSequenceCounterSource} bean
   * @param properties bound {@code shortener.*} settings; only {@code counterSeedOffset()} is read
   * @throws NullPointerException when any argument is {@code null}
   * @throws IllegalArgumentException when the configured offset is below {@code 62^2}
   */
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
   * @throws NullPointerException when {@code primary} or {@code fallback} is {@code null}
   * @throws IllegalArgumentException when {@code seedOffset} is below {@code 62^2}
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

  /**
   * The offset added to raw counter values before encoding.
   *
   * @return the seed offset, {@code >= 62^2}
   */
  public long seedOffset() {
    return seedOffset;
  }

  /**
   * Allocates the next short code.
   *
   * <p>Algorithm: call {@code primary.next()}; on any {@link RuntimeException} log one WARN naming
   * the two sources and the exception's simple class name (never its message, never a stack trace)
   * and call {@code fallback.next()} instead; then encode {@code raw + seedOffset} and pair it with
   * the {@code CodeSource} of the source that actually supplied the value.
   *
   * <p>Side effects: the counter I/O of the chosen source(s); consumed counter values are never
   * returned to a source even if the caller later fails to persist the code (accepted gap).
   *
   * @return the code and the counter that produced it
   * @throws RuntimeException when both the primary and the fallback counter fail (the fallback's
   *     exception is propagated; the primary's is only logged)
   * @throws IllegalStateException when a source returns a negative value
   * @throws ArithmeticException when {@code raw + seedOffset} overflows a {@code long}
   */
  public AllocatedCode allocate() {
    CounterSource source = primary;
    long raw;
    try {
      raw = primary.next();
    } catch (RuntimeException primaryFailure) {
      // Class name only: driver messages may embed the Redis URI and therefore credentials.
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

  /**
   * Applies the seed offset and base62-encodes a raw counter value.
   *
   * @param raw the value returned by a {@link CounterSource}, expected {@code >= 0}
   * @return {@code Base62Codec.encode(raw + seedOffset)}
   * @throws IllegalStateException when {@code raw} is negative, which would indicate a corrupted
   *     counter rather than a client error
   * @throws ArithmeticException when the addition overflows
   */
  private String encode(long raw) {
    if (raw < 0) {
      throw new IllegalStateException("counter source returned a negative value: " + raw);
    }
    return Base62Codec.encode(Math.addExact(raw, seedOffset));
  }
}
