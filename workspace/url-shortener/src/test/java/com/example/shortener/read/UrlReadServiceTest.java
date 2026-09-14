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
 */
class UrlReadServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String CODE = "promo2024";
  private static final String LONG_URL = "https://example.com/some/path?x=1";

  private final UrlCache cache = mock(UrlCache.class);
  private final UrlMappingRepository repository = mock(UrlMappingRepository.class);
  private final UrlReadService service = new UrlReadService(cache, repository, CLOCK);

  private static UrlMapping mapping(Instant expiresAt) {
    return new UrlMapping(
        CODE, LONG_URL, NOW.minus(Duration.ofDays(1)), expiresAt, CodeSource.REDIS);
  }

  // --- cache hit (AC-12) -----------------------------------------------------------------------

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

  @Test
  void cacheMissOnNeverExpiringMappingPopulatesCacheWithNullExpiry() {
    when(cache.get(CODE)).thenReturn(Optional.empty());
    when(repository.findByShortCode(CODE)).thenReturn(Optional.of(mapping(null)));

    assertThat(service.resolve(CODE)).isEqualTo(LONG_URL);

    verify(cache).put(CODE, LONG_URL, null);
  }

  // --- unknown (AC-8) --------------------------------------------------------------------------

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

  @Test
  void codeExpiringExactlyNowIsAlreadyExpired() {
    when(cache.get(CODE)).thenReturn(Optional.empty());
    when(repository.findByShortCode(CODE)).thenReturn(Optional.of(mapping(NOW)));

    assertThatThrownBy(() -> service.resolve(CODE)).isInstanceOf(ShortCodeExpiredException.class);
    verify(cache, never()).put(anyString(), anyString(), any());
  }

  @Test
  void codeExpiringOneSecondFromNowIsStillServedAndCached() {
    Instant expiresAt = NOW.plusSeconds(1);
    when(cache.get(CODE)).thenReturn(Optional.empty());
    when(repository.findByShortCode(CODE)).thenReturn(Optional.of(mapping(expiresAt)));

    assertThat(service.resolve(CODE)).isEqualTo(LONG_URL);
    verify(cache).put(CODE, LONG_URL, expiresAt);
  }

  // --- Redis down (AC-13) ----------------------------------------------------------------------

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

  @Test
  void rejectsNullShortCode() {
    assertThatThrownBy(() -> service.resolve(null)).isInstanceOf(NullPointerException.class);
    verifyNoInteractions(cache, repository);
  }
}
