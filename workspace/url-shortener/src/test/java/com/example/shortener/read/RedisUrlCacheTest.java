/*
 * RedisUrlCacheTest.java — unit tests for the Redis read cache and its degradation
 *
 * Layer: test. Pins RedisUrlCache without a Redis server: StringRedisTemplate and ValueOperations
 * are Mockito mocks, the clock is fixed. Covers the key layout shortener:url:{code} (AC-12), the
 * TTL rule min(expires_at - now, default) including its boundaries (AMB-9), the refusal to cache
 * expired mappings, the constructor guard on the TTL, and the AC-13 degradation contract: a
 * failing GET is a miss, a failing SET is a no-op, and the single WARN names the exception class
 * but never its message (which may carry the Redis host or credentials). Log output is captured
 * with a Logback ListAppender attached to the class logger.
 */
package com.example.shortener.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.shortener.config.ShortenerProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@link RedisUrlCache}: key layout and TTL bounds {@code min(expires_at - now, default 24h)}
 * (AC-12, AMB-9) plus Redis-failure degradation to a miss / no-op with a credential-free warning
 * (AC-13).
 *
 * <p>Everything Redis-related is mocked at the {@link StringRedisTemplate} / {@link
 * ValueOperations} level, so the tests assert the exact commands the cache issues ({@code set(key,
 * value, ttl)}) rather than Redis state. The interaction with a real Redis, including actual key
 * expiry, is covered by the {@code CacheAndFallbackIT} integration test. The Logback appender is
 * attached in {@link #setUp} and detached in {@link #tearDown} so log assertions do not leak
 * between tests.
 */
class RedisUrlCacheTest {

  /** The fixed "now" every TTL computation is relative to. */
  private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");

  /** Fixed clock handed to the cache so {@code put} sees {@link #NOW}. */
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  /** The production default of {@code shortener.cache-ttl} (AMB-9). */
  private static final Duration DEFAULT_TTL = Duration.ofHours(24);

  /** Short code under test. */
  private static final String CODE = "promo2024";

  /** The Redis key {@link #CODE} must map to: prefix plus code. */
  private static final String KEY = "shortener:url:promo2024";

  /** Value cached under {@link #KEY}. */
  private static final String LONG_URL = "https://example.com/some/path";

  /** Mocked template; {@code opsForValue()} is stubbed to return {@link #ops} in {@link #setUp}. */
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  /** Mocked value operations on which {@code get} / {@code set} are stubbed and verified. */
  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> ops = mock(ValueOperations.class);

  /** Cache under test with the explicit TTL and fixed clock. */
  private final RedisUrlCache cache = new RedisUrlCache(redis, DEFAULT_TTL, CLOCK);

  /** Captures every log event of {@link #cacheLogger} for the degradation assertions. */
  private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();

  /** The Logback logger behind {@code RedisUrlCache.log}. */
  private final Logger cacheLogger = (Logger) LoggerFactory.getLogger(RedisUrlCache.class);

  /** Wires the mocked template to the mocked value operations and starts capturing log events. */
  @BeforeEach
  void setUp() {
    when(redis.opsForValue()).thenReturn(ops);
    logEvents.start();
    cacheLogger.addAppender(logEvents);
  }

  /** Detaches and stops the log appender so events never leak into other tests. */
  @AfterEach
  void tearDown() {
    cacheLogger.detachAppender(logEvents);
    logEvents.stop();
  }

  // --- key layout ------------------------------------------------------------------------------

  /** The key prefix is exactly {@code shortener:url:} and the key of a code is prefix + code. */
  @Test
  void keyIsPrefixedShortCode() {
    assertThat(RedisUrlCache.KEY_PREFIX).isEqualTo("shortener:url:");
    assertThat(RedisUrlCache.keyOf(CODE)).isEqualTo(KEY);
  }

  // --- TTL bounds (AMB-9) ----------------------------------------------------------------------

  /** A mapping without {@code expires_at} gets the default TTL. */
  @Test
  void ttlIsDefaultWhenMappingNeverExpires() {
    assertThat(cache.ttlFor(null, NOW)).isEqualTo(DEFAULT_TTL);
  }

  /** An expiry closer than the default yields exactly the remaining time, to the millisecond. */
  @Test
  void ttlIsRemainingTimeWhenExpiryIsWithinTheDefault() {
    assertThat(cache.ttlFor(NOW.plus(Duration.ofHours(3)), NOW)).isEqualTo(Duration.ofHours(3));
    assertThat(cache.ttlFor(NOW.plusMillis(1500), NOW)).isEqualTo(Duration.ofMillis(1500));
  }

  /** An expiry further away than the default, even by one millisecond, is capped at the default. */
  @Test
  void ttlIsCappedByTheDefaultWhenExpiryIsFurtherAway() {
    assertThat(cache.ttlFor(NOW.plus(Duration.ofDays(30)), NOW)).isEqualTo(DEFAULT_TTL);
    assertThat(cache.ttlFor(NOW.plus(DEFAULT_TTL).plusMillis(1), NOW)).isEqualTo(DEFAULT_TTL);
  }

  /** Remaining time equal to the default yields the default (the comparison is strict-less). */
  @Test
  void ttlEqualsDefaultExactlyAtTheBoundary() {
    assertThat(cache.ttlFor(NOW.plus(DEFAULT_TTL), NOW)).isEqualTo(DEFAULT_TTL);
  }

  /** A mapping expiring exactly now or in the past yields a zero TTL, the "do not cache" signal. */
  @Test
  void ttlIsZeroForExpiredOrExpiringNowMappings() {
    assertThat(cache.ttlFor(NOW, NOW)).isEqualTo(Duration.ZERO);
    assertThat(cache.ttlFor(NOW.minusSeconds(1), NOW)).isEqualTo(Duration.ZERO);
  }

  /**
   * The Spring constructor takes the TTL from {@code ShortenerProperties.cacheTtl}, and both
   * constructors reject a zero or negative TTL with {@link IllegalArgumentException}.
   */
  @Test
  void defaultTtlComesFromPropertiesAndMustBePositive() {
    ShortenerProperties properties =
        new ShortenerProperties(
            ShortenerProperties.DEFAULT_BASE_URL,
            ShortenerProperties.DEFAULT_COUNTER_BATCH_SIZE,
            Duration.ofHours(6),
            ShortenerProperties.DEFAULT_COUNTER_SEED_OFFSET);

    assertThat(new RedisUrlCache(redis, properties).defaultTtl()).isEqualTo(Duration.ofHours(6));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new RedisUrlCache(redis, Duration.ZERO, CLOCK));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new RedisUrlCache(redis, Duration.ofSeconds(-1), CLOCK));
  }

  // --- get / put -------------------------------------------------------------------------------

  /** A value present under the key is returned as a non-empty Optional. */
  @Test
  void getReturnsCachedValueOnHit() {
    when(ops.get(KEY)).thenReturn(LONG_URL);

    assertThat(cache.get(CODE)).contains(LONG_URL);
  }

  /** A {@code null} from Redis (key absent) is an empty Optional. */
  @Test
  void getReturnsEmptyOnMiss() {
    when(ops.get(KEY)).thenReturn(null);

    assertThat(cache.get(CODE)).isEmpty();
  }

  /** Caching a never-expiring mapping issues {@code SET key url} with the default TTL. */
  @Test
  void putUsesDefaultTtlForNeverExpiringMapping() {
    cache.put(CODE, LONG_URL, null);

    verify(ops).set(KEY, LONG_URL, DEFAULT_TTL);
  }

  /** Caching a mapping that expires within the default issues SET with the remaining time. */
  @Test
  void putUsesRemainingTimeAsTtl() {
    cache.put(CODE, LONG_URL, NOW.plus(Duration.ofMinutes(90)));

    verify(ops).set(KEY, LONG_URL, Duration.ofMinutes(90));
  }

  /** Caching a mapping that expires after the default issues SET with the default TTL. */
  @Test
  void putCapsTtlAtTheDefault() {
    cache.put(CODE, LONG_URL, NOW.plus(Duration.ofDays(7)));

    verify(ops).set(KEY, LONG_URL, DEFAULT_TTL);
  }

  /** Mappings expired one second ago or expiring exactly now are never written to Redis. */
  @Test
  void putSkipsAlreadyExpiredMappings() {
    cache.put(CODE, LONG_URL, NOW.minusSeconds(1));
    cache.put(CODE, LONG_URL, NOW);

    verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
  }

  // --- degradation (AC-13) ---------------------------------------------------------------------

  /**
   * A connection failure on GET is answered as a miss, and exactly one WARN is logged that names
   * the command, the key and the exception class but neither the message, the host nor a stack
   * trace.
   */
  @Test
  void getFailureIsAMissAndLogsADegradationWarningWithoutTheExceptionMessage() {
    String message = "Unable to connect to redis.internal:6379";
    when(ops.get(KEY)).thenThrow(new RedisConnectionFailureException(message));

    assertThat(cache.get(CODE)).isEmpty();

    ILoggingEvent warning = singleWarning();
    assertThat(warning.getFormattedMessage())
        .contains("cache degraded", "GET", KEY, "RedisConnectionFailureException")
        .doesNotContain(message)
        .doesNotContain("redis.internal");
    assertThat(warning.getThrowableProxy()).isNull();
  }

  /**
   * A timeout on SET does not propagate to the caller, and exactly one WARN is logged with the same
   * credential-free shape as for GET.
   */
  @Test
  void putFailureIsANoOpAndLogsADegradationWarningWithoutTheExceptionMessage() {
    String message = "Redis command timed out after 2000 ms against redis.internal:6379";
    doThrow(new QueryTimeoutException(message))
        .when(ops)
        .set(anyString(), anyString(), any(Duration.class));

    assertThatCode(() -> cache.put(CODE, LONG_URL, null)).doesNotThrowAnyException();

    ILoggingEvent warning = singleWarning();
    assertThat(warning.getFormattedMessage())
        .contains("cache degraded", "SET", KEY, "QueryTimeoutException")
        .doesNotContain(message)
        .doesNotContain("redis.internal");
    assertThat(warning.getThrowableProxy()).isNull();
  }

  /**
   * A failure raised by the template itself (before any command, here from {@code opsForValue()})
   * is degraded in the same way for both GET and SET, producing one WARN each.
   */
  @Test
  void templateFailureBeforeTheCommandIsDegradedToo() {
    when(redis.opsForValue()).thenThrow(new IllegalStateException("template not initialised"));

    assertThat(cache.get(CODE)).isEmpty();
    assertThatCode(() -> cache.put(CODE, LONG_URL, null)).doesNotThrowAnyException();
    assertThat(warnings()).hasSize(2);
  }

  // --- helpers ---------------------------------------------------------------------------------

  /**
   * All captured events at WARN level, in order.
   *
   * @return the WARN events emitted by the cache logger during the current test
   */
  private List<ILoggingEvent> warnings() {
    return logEvents.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
  }

  /**
   * Asserts that exactly one WARN was logged and returns it.
   *
   * @return the single WARN event
   */
  private ILoggingEvent singleWarning() {
    List<ILoggingEvent> warnings = warnings();
    assertThat(warnings).hasSize(1);
    return warnings.get(0);
  }
}
