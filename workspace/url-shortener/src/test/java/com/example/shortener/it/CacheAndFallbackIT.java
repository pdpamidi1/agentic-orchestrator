package com.example.shortener.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortener.domain.CodeSource;
import com.example.shortener.domain.UrlMapping;
import com.example.shortener.read.RedisUrlCache;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Redis behaviour end to end: a redirect populates the cache and later redirects are served from it
 * (AC-12), the TTL never exceeds the remaining expiry (AMB-9) and an unreachable Redis still yields
 * {@code 201} with {@code code_source = db_sequence} on write (AC-11) and a {@code 302} served from
 * Postgres on read (AC-13).
 *
 * <p>The outage is produced by pausing the Redis container: every command then hits the configured
 * client timeout, which is exactly how a dead or partitioned Redis looks from the application. The
 * container is unpaused again before the method returns so the shared test context and the other IT
 * classes are unaffected; the outage test therefore runs last.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CacheAndFallbackIT extends AbstractIntegrationTest {

  private static final Duration DEFAULT_CACHE_TTL = Duration.ofHours(24);

  // --- cache hit (AC-12) -----------------------------------------------------------------------

  @Test
  @Order(1)
  void firstRedirectPopulatesTheCacheAndLaterRedirectsAreServedFromIt() {
    String longUrl = uniqueLongUrl();
    String shortCode = createShortCode(longUrl);
    String key = RedisUrlCache.keyOf(shortCode);
    assertThat(redis.hasKey(key)).as("creation does not warm the cache").isFalse();

    HttpResponse<String> first = get("/" + shortCode);
    assertThat(first.statusCode()).isEqualTo(302);
    assertThat(redis.opsForValue().get(key)).isEqualTo(longUrl);

    // Remove the row: only the cache can answer now.
    repository.deleteById(shortCode);
    repository.flush();
    assertThat(repository.findByShortCode(shortCode)).isEmpty();

    HttpResponse<String> second = get("/" + shortCode);

    assertThat(second.statusCode()).isEqualTo(302);
    assertThat(header(second, "Location")).isEqualTo(longUrl);
    assertThat(header(second, "Cache-Control")).isEqualTo("private");
  }

  // --- TTL bound (AMB-9) -----------------------------------------------------------------------

  @Test
  @Order(2)
  void cacheTtlNeverExceedsTheRemainingExpiry() {
    Duration remaining = Duration.ofMinutes(30);
    Instant expiry = Instant.now().plus(remaining).truncatedTo(ChronoUnit.SECONDS);
    HttpResponse<String> created =
        post(URLS_PATH, createRequest(uniqueLongUrl(), null, expiry.toString()));
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    String shortCode = (String) json(created.body()).get("short_code");

    assertThat(get("/" + shortCode).statusCode()).isEqualTo(302);

    Long ttlSeconds = redis.getExpire(RedisUrlCache.keyOf(shortCode), TimeUnit.SECONDS);
    assertThat(ttlSeconds).isNotNull().isPositive().isLessThanOrEqualTo(remaining.toSeconds());
  }

  @Test
  @Order(3)
  void neverExpiringLinkIsCachedForTheDefaultTtl() {
    String shortCode = createShortCode(uniqueLongUrl());

    assertThat(get("/" + shortCode).statusCode()).isEqualTo(302);

    Long ttlSeconds = redis.getExpire(RedisUrlCache.keyOf(shortCode), TimeUnit.SECONDS);
    assertThat(ttlSeconds)
        .isNotNull()
        .isPositive()
        .isLessThanOrEqualTo(DEFAULT_CACHE_TTL.toSeconds())
        .isGreaterThan(DEFAULT_CACHE_TTL.minusMinutes(5).toSeconds());
  }

  @Test
  @Order(4)
  void expiredMappingIsNeverCached() {
    Instant now = Instant.now();
    repository.saveAndFlush(
        new UrlMapping(
            "staleIT",
            "https://example.com/stale",
            now.minus(2, ChronoUnit.DAYS),
            now.minus(1, ChronoUnit.DAYS),
            CodeSource.REDIS));

    assertThat(get("/staleIT").statusCode()).isEqualTo(410);

    assertThat(redis.hasKey(RedisUrlCache.keyOf("staleIT"))).isFalse();
  }

  // --- Redis outage (AC-11, AC-13) -------------------------------------------------------------

  @Test
  @Order(5)
  void unreachableRedisFallsBackToDbSequenceOnWriteAndPostgresOnRead() {
    String cachedBeforeOutage = uniqueLongUrl();
    String warmCode = createShortCode(cachedBeforeOutage);
    String coldUrl = uniqueLongUrl();
    String coldCode = createShortCode(coldUrl);
    assertThat(get("/" + warmCode).statusCode()).isEqualTo(302);
    assertThat(redis.hasKey(RedisUrlCache.keyOf(warmCode))).isTrue();
    assertThat(redis.hasKey(RedisUrlCache.keyOf(coldCode))).isFalse();

    String fallbackUrl = uniqueLongUrl();
    String fallbackCode;
    pauseRedis();
    try {
      // Write: the Redis counter is unreachable, the Postgres sequence takes over (AC-11).
      HttpResponse<String> created = post(URLS_PATH, createRequest(fallbackUrl, null, null));
      assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
      Map<String, Object> body = json(created.body());
      assertThat(body.get("code_source")).isEqualTo("db_sequence");
      fallbackCode = (String) body.get("short_code");
      UrlMapping row = repository.findByShortCode(fallbackCode).orElseThrow();
      assertThat(row.getCodeSource()).isEqualTo(CodeSource.DB_SEQUENCE);
      assertThat(row.getLongUrl()).isEqualTo(fallbackUrl);

      // Read: cache lookups fail, the redirect is served straight from Postgres (AC-13).
      HttpResponse<String> cold = get("/" + coldCode);
      assertThat(cold.statusCode()).isEqualTo(302);
      assertThat(header(cold, "Location")).isEqualTo(coldUrl);
      assertThat(header(cold, "Cache-Control")).isEqualTo("private");

      HttpResponse<String> warm = get("/" + warmCode);
      assertThat(warm.statusCode()).isEqualTo(302);
      assertThat(header(warm, "Location")).isEqualTo(cachedBeforeOutage);

      HttpResponse<String> unknown = get("/unknownDuringOutage");
      assertProblem(unknown, 404);
    } finally {
      unpauseRedis();
    }

    // Recovery: Redis answers again, the primary counter and the cache are back in use.
    awaitRedis();
    HttpResponse<String> afterRecovery = get("/" + fallbackCode);
    assertThat(afterRecovery.statusCode()).isEqualTo(302);
    assertThat(redis.opsForValue().get(RedisUrlCache.keyOf(fallbackCode))).isEqualTo(fallbackUrl);
    HttpResponse<String> recoveredWrite =
        post(URLS_PATH, createRequest(uniqueLongUrl(), null, null));
    assertThat(recoveredWrite.statusCode()).as(recoveredWrite.body()).isEqualTo(201);
    assertThat(json(recoveredWrite.body()).get("code_source")).isEqualTo("redis");
  }

  private static void pauseRedis() {
    REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
  }

  private static void unpauseRedis() {
    REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
  }

  /** Waits until the application's Redis connection answers again after the pause. */
  private void awaitRedis() {
    Instant deadline = Instant.now().plusSeconds(60);
    while (true) {
      try {
        redis.opsForValue().get("shortener:it:probe");
        return;
      } catch (RuntimeException notYet) {
        if (Instant.now().isAfter(deadline)) {
          throw notYet;
        }
        try {
          Thread.sleep(250);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("interrupted while waiting for Redis", e);
        }
      }
    }
  }
}
