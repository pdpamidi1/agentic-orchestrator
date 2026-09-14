package com.example.shortener.read;

import com.example.shortener.config.ShortenerProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link UrlCache} backed by Redis: one string key {@code shortener:url:{short_code}} per mapping
 * holding the long URL (AC-12).
 *
 * <p>The TTL of an entry is {@code min(expires_at - now, shortener.cache-ttl)}; entries of
 * never-expiring mappings live for the default TTL (24h, AMB-9). Because the TTL never outlives
 * {@code expires_at}, Redis evicts an entry no later than the mapping expires and an expired link
 * is never served from the cache.
 *
 * <p>This is the boundary class that owns the Redis technology on the read side (with {@code
 * RedisBatchCounterSource} on the write side). Every Redis failure on {@code GET} or {@code SET} is
 * caught here, logged as a cache degradation warning and turned into a miss / no-op so the redirect
 * is served straight from Postgres (AC-13). The warning names the failure class only: exception
 * messages may embed connection URIs and therefore credentials, so they are logged at DEBUG only.
 */
@Component
public class RedisUrlCache implements UrlCache {

  /** Prefix of every cache key; the short code follows. */
  public static final String KEY_PREFIX = "shortener:url:";

  private static final Logger log = LoggerFactory.getLogger(RedisUrlCache.class);

  private final StringRedisTemplate redis;
  private final Duration defaultTtl;
  private final Clock clock;

  /** Creates a cache using the configured {@code shortener.cache-ttl} and the system UTC clock. */
  @Autowired
  public RedisUrlCache(StringRedisTemplate redis, ShortenerProperties properties) {
    this(redis, Objects.requireNonNull(properties, "properties").cacheTtl(), Clock.systemUTC());
  }

  /**
   * Creates a cache with an explicit default TTL and clock.
   *
   * @param redis template used for {@code GET} / {@code SET ... PX}
   * @param defaultTtl TTL of entries whose mapping never expires and upper bound for all others;
   *     must be positive
   * @param clock source of "now" for the remaining-time computation
   */
  public RedisUrlCache(StringRedisTemplate redis, Duration defaultTtl, Clock clock) {
    this.redis = Objects.requireNonNull(redis, "redis");
    this.defaultTtl = Objects.requireNonNull(defaultTtl, "defaultTtl");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (defaultTtl.isZero() || defaultTtl.isNegative()) {
      throw new IllegalArgumentException("defaultTtl must be positive but was " + defaultTtl);
    }
  }

  /** The configured default / maximum TTL. */
  public Duration defaultTtl() {
    return defaultTtl;
  }

  /** Renders the Redis key of a short code: {@code shortener:url:{short_code}}. */
  public static String keyOf(String shortCode) {
    return KEY_PREFIX + Objects.requireNonNull(shortCode, "shortCode");
  }

  /**
   * TTL an entry receives for the given expiry: the remaining time to {@code expiresAt}, capped by
   * the default TTL; the default TTL when {@code expiresAt} is {@code null}; {@link Duration#ZERO}
   * when the mapping is already expired (such an entry must not be written).
   *
   * @param expiresAt expiry instant of the mapping, or {@code null} when it never expires
   * @param now the reference instant
   */
  public Duration ttlFor(Instant expiresAt, Instant now) {
    Objects.requireNonNull(now, "now");
    if (expiresAt == null) {
      return defaultTtl;
    }
    Duration remaining = Duration.between(now, expiresAt);
    if (remaining.isZero() || remaining.isNegative()) {
      return Duration.ZERO;
    }
    return remaining.compareTo(defaultTtl) < 0 ? remaining : defaultTtl;
  }

  @Override
  public Optional<String> get(String shortCode) {
    String key = keyOf(shortCode);
    try {
      return Optional.ofNullable(redis.opsForValue().get(key));
    } catch (RuntimeException e) {
      degraded("GET", key, e);
      return Optional.empty();
    }
  }

  @Override
  public void put(String shortCode, String longUrl, Instant expiresAt) {
    String key = keyOf(shortCode);
    Objects.requireNonNull(longUrl, "longUrl");
    Duration ttl = ttlFor(expiresAt, clock.instant());
    if (ttl.isZero()) {
      log.debug("Not caching {}: mapping already expired at {}", key, expiresAt);
      return;
    }
    try {
      redis.opsForValue().set(key, longUrl, ttl);
    } catch (RuntimeException e) {
      degraded("SET", key, e);
    }
  }

  /** Logs a cache degradation without the exception message (it may carry connection details). */
  private static void degraded(String command, String key, RuntimeException e) {
    log.warn(
        "Redis cache degraded: {} {} failed with {}; serving redirect from database",
        command,
        key,
        e.getClass().getSimpleName());
    log.debug("Redis cache failure detail for {} {}", command, key, e);
  }
}
