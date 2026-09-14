/*
 * UrlReadServiceTest.java — unit tests for short-code resolution (cache, database, expiry)
 *
 * Layer: test. Pins UrlReadService with Mockito mocks of UrlCache and UrlMappingRepository and a
 * fixed Clock: a cache hit never touches Postgres (AC-12), a miss loads the row and populates the
 * cache with the mapping's expiry (AC-7), unknown -> ShortCodeNotFoundException (AC-8), expired
 * or expiring exactly now -> ShortCodeExpiredException and never cached (AC-9). The two "Redis
 * down" tests wire the real RedisUrlCache over a throwing StringRedisTemplate mock to prove that
 * the service serves from Postgres and still answers 404/410 without catching anything (AC-13).
 */
package com.example.shortener.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.shortener.api.error.ShortCodeExpiredException;
import com.example.shortener.api.error.ShortCodeNotFoundException;
import com.example.shortener.domain.CodeSource;
import com.example.shortener.domain.UrlMapping;
import com.example.shortener.domain.UrlMappingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@link UrlReadService} with mocked cache and repository: cache hit never touches Postgres
 * (AC-12), miss loads and populates the cache (AC-7), unknown code is 404 (AC-8), expired code is
 * 410 and never cached (AC-9), Redis-down bypass serves from Postgres (AC-13).
 *
 * <p>The service is constructed with the explicit-clock constructor so expiry boundaries ("exactly
 * now", "one second from now") are deterministic. HTTP status codes are not asserted here; the
 * exception types thrown are the contract that {@code GlobalExceptionHandler} maps to 404 / 410,
 * which {@link RedirectControllerTest} covers.
 */
class UrlReadServiceTest {

  /** The fixed "now" the service compares {@code expires_at} against. */
  private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");

  /** Fixed clock injected into the service under test. */
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  /** Short code under test. */
  private static final String CODE = "promo2024";

  /** Long URL stored for {@link #CODE}. */
  private static final String LONG_URL = "https://example.com/some/path?x=1";

  /** Mocked cache; stubbed per test to hit or miss. */
  private final UrlCache cache = mock(UrlCache.class);

  /** Mocked repository; stubbed per test to return a mapping or nothing. */
  private final UrlMappingRepository repository = mock(UrlMappingRepository.class);

  /** Service under test over the two mocks and the fixed clock. */
  private final UrlReadService service = new UrlReadService(cache, repository, CLOCK);

  /**
   * Builds a mapping for {@link #CODE} created one day ago with the given expiry.
   *
   * @param expiresAt the expiry instant, or {@code null} for a never-expiring link
   * @return a fresh {@link UrlMapping} with {@code code_source = redis}
   */
  private static UrlMapping mapping(Instant expiresAt) {
    return new UrlMapping(
        CODE, LONG_URL, NOW.minus(Duration.ofDays(1)), expiresAt, CodeSource.REDIS);
  }

  // --- cache hit (AC-12) -----------------------------------------------------------------------

  /**
   * On a cache hit the cached URL is returned, the repository is never called and the cache sees
   * exactly one {@code get} and no {@code put}.
   */
  @Test
  void cacheHitReturnsCachedUrlAndNeverTouchesTheRepository() {
    when(cache.get(CODE)).thenReturn(Optional.of(LONG_URL));

    String resolved = service.resolve(CODE);

    assertThat(resolved).isEqualTo(LONG_URL);
    verifyNoInteractions(repository);
    verify(cache).get(CODE);
    verify(cache, never()).put(anyString(), anyString(), any());
    verifyNoMoreInteractions(cache);
  }

  // --- cache miss (AC-7) -----------------------------------------------------------------------

  /**
   * On a miss the mapping is loaded from the repository, its URL is returned and the cache is
   * populated with the mapping's own {@code expires_at} so the cache can bound the TTL.
   */
  @Test
  void cacheMissLoadsFromRepositoryAndPopulatesCacheWithExpiry() {
    Instant expiresAt = NOW.plus(Duration.ofHours(3));
    when(cache.get(CODE)).thenReturn(Optional.empty());
    when(repository.findByShortCode(CODE)).thenReturn(Optional.of(mapping(expiresAt)));

    String resolved = service.resolve(CODE);

    assertThat(resolved).isEqualTo(LONG_URL);
    verify(repository).findByShortCode(CODE);
    verify(cache).put(CODE, LONG_URL, expiresAt);
  }

  /** A never-expiring mapping is cached with a {@code null} expiry; the cache applies its TTL. */
  @Test
  void cacheMissOnNeverExpiringMappingPopulatesCacheWithNullExpiry() {
    when(cache.get(CODE)).thenReturn(Optional.empty());
    when(repository.findByShortCode(CODE)).thenReturn(Optional.of(mapping(null)));

    assertThat(service.resolve(CODE)).isEqualTo(LONG_URL);

    verify(cache).put(CODE, LONG_URL, null);
  }

  // --- unknown (AC-8) --------------------------------------------------------------------------

  /**
   * A code missing from both cache and repository raises {@link ShortCodeNotFoundException}
   * carrying the code, and nothing is written to the cache (no negative caching).
   */
  @Test
  void unknownCodeIsShortCodeNotFoundAndNothingIsCached() {
    when(cache.get(CODE)).thenReturn(Optional.empty());
    when(repository.findByShortCode(CODE)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.resolve(CODE))
        .isInstanceOf(ShortCodeNotFoundException.class)
        .hasMessageContaining(CODE)
        .extracting("shortCode")
        .isEqualTo(CODE);
    verify(cache, never()).put(anyString(), anyString(), any());
  }

  // --- expired (AC-9) --------------------------------------------------------------------------

  /**
   * A mapping whose expiry lies in the past raises {@link ShortCodeExpiredException} carrying the
   * expiry instant, and the expired mapping is never written to the cache.
   */
  @Test
  void expiredCodeIsShortCodeExpiredAndIsNotCached() {
    Instant expiredAt = NOW.minus(Duration.ofMinutes(1));
    when(cache.get(CODE)).thenReturn(Optional.empty());
    when(repository.findByShortCode(CODE)).thenReturn(Optional.of(mapping(expiredAt)));

    assertThatThrownBy(() -> service.resolve(CODE))
        .isInstanceOf(ShortCodeExpiredException.class)
        .hasMessageContaining(CODE)
        .extracting("expiredAt")
        .isEqualTo(expiredAt);
    verify(cache, never()).put(anyString(), anyString(), any());
  }

  /** Boundary: an expiry equal to "now" counts as expired (410), not as still live. */
  @Test
  void codeExpiringExactlyNowIsAlreadyExpired() {
    when(cache.get(CODE)).thenReturn(Optional.empty());
    when(repository.findByShortCode(CODE)).thenReturn(Optional.of(mapping(NOW)));

    assertThatThrownBy(() -> service.resolve(CODE)).isInstanceOf(ShortCodeExpiredException.class);
    verify(cache, never()).put(anyString(), anyString(), any());
  }

  /** Boundary: an expiry one second in the future is still served and cached with that expiry. */
  @Test
  void codeExpiringOneSecondFromNowIsStillServedAndCached() {
    Instant expiresAt = NOW.plusSeconds(1);
    when(cache.get(CODE)).thenReturn(Optional.empty());
    when(repository.findByShortCode(CODE)).thenReturn(Optional.of(mapping(expiresAt)));

    assertThat(service.resolve(CODE)).isEqualTo(LONG_URL);
    verify(cache).put(CODE, LONG_URL, expiresAt);
  }

  // --- Redis down (AC-13) ----------------------------------------------------------------------

  /**
   * With the real {@link RedisUrlCache} over a template whose GET and SET both throw connection
   * failures, the service still resolves the URL from the repository and both Redis commands were
   * attempted (the SET with the TTL bounded by the mapping's remaining hour).
   */
  @Test
  void redisDownOnGetAndSetIsBypassedAndTheRedirectIsServedFromPostgres() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("refused"));
    doThrow(new RedisConnectionFailureException("refused"))
        .when(ops)
        .set(anyString(), anyString(), any(Duration.class));
    UrlReadService degraded =
        new UrlReadService(
            new RedisUrlCache(redis, Duration.ofHours(24), CLOCK), repository, CLOCK);
    when(repository.findByShortCode(CODE))
        .thenReturn(Optional.of(mapping(NOW.plus(Duration.ofHours(1)))));

    String resolved = degraded.resolve(CODE);

    assertThat(resolved).isEqualTo(LONG_URL);
    verify(repository).findByShortCode(CODE);
    verify(ops).get(RedisUrlCache.keyOf(CODE));
    verify(ops).set(RedisUrlCache.keyOf(CODE), LONG_URL, Duration.ofHours(1));
  }

  /**
   * A Redis outage changes nothing about the 404 / 410 decisions: an unknown code still raises
   * {@link ShortCodeNotFoundException} and an expired one {@link ShortCodeExpiredException}.
   */
  @Test
  void redisDownStillYields404ForUnknownAnd410ForExpiredCodes() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("refused"));
    UrlReadService degraded =
        new UrlReadService(
            new RedisUrlCache(redis, Duration.ofHours(24), CLOCK), repository, CLOCK);
    when(repository.findByShortCode("missing")).thenReturn(Optional.empty());
    when(repository.findByShortCode(CODE))
        .thenReturn(Optional.of(mapping(NOW.minus(Duration.ofDays(1)))));

    assertThatThrownBy(() -> degraded.resolve("missing"))
        .isInstanceOf(ShortCodeNotFoundException.class);
    assertThatThrownBy(() -> degraded.resolve(CODE)).isInstanceOf(ShortCodeExpiredException.class);
  }

  // --- guards ----------------------------------------------------------------------------------

  /** A {@code null} short code fails fast with {@link NullPointerException} before any lookup. */
  @Test
  void rejectsNullShortCode() {
    assertThatThrownBy(() -> service.resolve(null)).isInstanceOf(NullPointerException.class);
    verifyNoInteractions(cache, repository);
  }
}
