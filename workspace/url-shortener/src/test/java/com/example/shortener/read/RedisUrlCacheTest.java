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
 */
class RedisUrlCacheTest {

  private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final Duration DEFAULT_TTL = Duration.ofHours(24);
  private static final String CODE = "promo2024";
  private static final String KEY = "shortener:url:promo2024";
  private static final String LONG_URL = "https://example.com/some/path";

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> ops = mock(ValueOperations.class);

  private final RedisUrlCache cache = new RedisUrlCache(redis, DEFAULT_TTL, CLOCK);

  private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
  private final Logger cacheLogger = (Logger) LoggerFactory.getLogger(RedisUrlCache.class);

  @BeforeEach
  void setUp() {
    when(redis.opsForValue()).thenReturn(ops);
    logEvents.start();
    cacheLogger.addAppender(logEvents);
  }

  @AfterEach
  void tearDown() {
    cacheLogger.detachAppender(logEvents);
    logEvents.stop();
  }

  // --- key layout ------------------------------------------------------------------------------

  @Test
  void keyIsPrefixedShortCode() {
    assertThat(RedisUrlCache.KEY_PREFIX).isEqualTo("shortener:url:");
    assertThat(RedisUrlCache.keyOf(CODE)).isEqualTo(KEY);
  }

  // --- TTL bounds (AMB-9) ----------------------------------------------------------------------

  @Test
  void ttlIsDefaultWhenMappingNeverExpires() {
    assertThat(cache.ttlFor(null, NOW)).isEqualTo(DEFAULT_TTL);
  }

  @Test
  void ttlIsRemainingTimeWhenExpiryIsWithinTheDefault() {
    assertThat(cache.ttlFor(NOW.plus(Duration.ofHours(3)), NOW)).isEqualTo(Duration.ofHours(3));
    assertThat(cache.ttlFor(NOW.plusMillis(1500), NOW)).isEqualTo(Duration.ofMillis(1500));
  }

  @Test
  void ttlIsCappedByTheDefaultWhenExpiryIsFurtherAway() {
    assertThat(cache.ttlFor(NOW.plus(Duration.ofDays(30)), NOW)).isEqualTo(DEFAULT_TTL);
    assertThat(cache.ttlFor(NOW.plus(DEFAULT_TTL).plusMillis(1), NOW)).isEqualTo(DEFAULT_TTL);
  }

  @Test
  void ttlEqualsDefaultExactlyAtTheBoundary() {
    assertThat(cache.ttlFor(NOW.plus(DEFAULT_TTL), NOW)).isEqualTo(DEFAULT_TTL);
  }

  @Test
  void ttlIsZeroForExpiredOrExpiringNowMappings() {
    assertThat(cache.ttlFor(NOW, NOW)).isEqualTo(Duration.ZERO);
    assertThat(cache.ttlFor(NOW.minusSeconds(1), NOW)).isEqualTo(Duration.ZERO);
  }

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

  @Test
  void getReturnsCachedValueOnHit() {
    when(ops.get(KEY)).thenReturn(LONG_URL);

    assertThat(cache.get(CODE)).contains(LONG_URL);
  }

  @Test
  void getReturnsEmptyOnMiss() {
    when(ops.get(KEY)).thenReturn(null);

    assertThat(cache.get(CODE)).isEmpty();
  }

  @Test
  void putUsesDefaultTtlForNeverExpiringMapping() {
    cache.put(CODE, LONG_URL, null);

    verify(ops).set(KEY, LONG_URL, DEFAULT_TTL);
  }

  @Test
  void putUsesRemainingTimeAsTtl() {
    cache.put(CODE, LONG_URL, NOW.plus(Duration.ofMinutes(90)));

    verify(ops).set(KEY, LONG_URL, Duration.ofMinutes(90));
  }

  @Test
  void putCapsTtlAtTheDefault() {
    cache.put(CODE, LONG_URL, NOW.plus(Duration.ofDays(7)));

    verify(ops).set(KEY, LONG_URL, DEFAULT_TTL);
  }

  @Test
  void putSkipsAlreadyExpiredMappings() {
    cache.put(CODE, LONG_URL, NOW.minusSeconds(1));
    cache.put(CODE, LONG_URL, NOW);

    verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
  }

  // --- degradation (AC-13) ---------------------------------------------------------------------

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

  @Test
  void templateFailureBeforeTheCommandIsDegradedToo() {
    when(redis.opsForValue()).thenThrow(new IllegalStateException("template not initialised"));

    assertThat(cache.get(CODE)).isEmpty();
    assertThatCode(() -> cache.put(CODE, LONG_URL, null)).doesNotThrowAnyException();
    assertThat(warnings()).hasSize(2);
  }

  // --- helpers ---------------------------------------------------------------------------------

  private List<ILoggingEvent> warnings() {
    return logEvents.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
  }

  private ILoggingEvent singleWarning() {
    List<ILoggingEvent> warnings = warnings();
    assertThat(warnings).hasSize(1);
    return warnings.get(0);
  }
}
