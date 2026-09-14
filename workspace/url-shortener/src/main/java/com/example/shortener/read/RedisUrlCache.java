/*
 * RedisUrlCache.java — Redis implementation of UrlCache with graceful degradation
 *
 * Layer: read. The one read-side class allowed to touch Spring Data Redis (layering rule in
 * sdlc.ArchitectureTest). Stores each mapping as the string key shortener:url:{short_code} whose
 * value is the long URL, with TTL min(expires_at - now, shortener.cache-ttl) so that nothing stale
 * survives an expiry (AC-12, AMB-9). Every Redis failure on GET or SET is caught here, logged as a
 * credential-free WARN and turned into a miss / no-op so UrlReadService keeps serving redirects
 * from Postgres (AC-13, docs/operations.md section 1.1). Collaborators: StringRedisTemplate,
 * ShortenerProperties (default TTL), Clock (testable "now").
 */
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
 *
 * <p>Why degrade instead of fail: Redis is an accelerator, Postgres is the system of record. A
 * redirect that is correct but slower (one extra database round trip, plus up to the 2s Redis
 * timeout while Redis is down) is preferable to a 5xx, and recovery is automatic because the next
 * request after Redis returns simply writes through again. There is no circuit breaker; see {@code
 * docs/operations.md} for the operational consequences.
 *
 * <p>Why cache-aside with a bounded TTL: entries are populated lazily on the first miss, never by
 * the write surface, so the two surfaces need no coordination. Capping the TTL at {@code
 * expires_at} means the 410 decision never depends on cache invalidation; the default TTL bounds
 * memory for links that never expire.
 *
 * <p>Profile gating: this bean is not itself gated. It is only pulled into a context by {@link
 * UrlReadService}, which is required by {@code RedirectController} ({@code @Profile("!write")}),
 * and it is a plain component on every surface. Write-only instances therefore carry the bean
 * without using it; it never performs a Redis call on its own.
 *
 * <p>Invariants: {@code defaultTtl} is strictly positive; {@link #ttlFor} never returns a negative
 * duration; {@link #put} never writes a zero TTL. Thread-safety: the class is immutable after
 * construction and {@link StringRedisTemplate} is thread-safe, so a single instance serves all
 * request threads.
 */
@Component
public class RedisUrlCache implements UrlCache {

  /**
   * Prefix of every cache key; the short code follows. Namespacing the keys keeps them apart from
   * the write side's counter key ({@code shortener:counter}) in the same logical database and lets
   * operators inspect or flush the cache with a {@code shortener:url:*} pattern.
   */
  public static final String KEY_PREFIX = "shortener:url:";

  /** Emits the degradation WARN (class name only) and the DEBUG line carrying the full cause. */
  private static final Logger log = LoggerFactory.getLogger(RedisUrlCache.class);

  /** String/String template: keys and values are plain UTF-8 strings, the value is the long URL. */
  private final StringRedisTemplate redis;

  /**
   * TTL for never-expiring mappings and the upper bound for all others ({@code
   * shortener.cache-ttl}, default 24h, AMB-9). Always strictly positive.
   */
  private final Duration defaultTtl;

  /** Source of "now" for the remaining-time computation; fixed in tests, UTC system clock live. */
  private final Clock clock;

  /**
   * Creates a cache using the configured {@code shortener.cache-ttl} and the system UTC clock.
   *
   * <p>This is the constructor Spring uses ({@link Autowired}); it delegates to the explicit one.
   *
   * @param redis the Spring Data Redis string template
   * @param properties bound {@code shortener.*} settings; only {@code cacheTtl} is used
   * @throws NullPointerException when {@code properties} or {@code redis} is {@code null}
   * @throws IllegalArgumentException when the configured TTL is zero or negative
   */
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
   * @throws NullPointerException when any argument is {@code null}
   * @throws IllegalArgumentException when {@code defaultTtl} is zero or negative (a zero TTL would
   *     make every entry vanish immediately and a negative one is rejected by Redis)
   */
  public RedisUrlCache(StringRedisTemplate redis, Duration defaultTtl, Clock clock) {
    this.redis = Objects.requireNonNull(redis, "redis");
    this.defaultTtl = Objects.requireNonNull(defaultTtl, "defaultTtl");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (defaultTtl.isZero() || defaultTtl.isNegative()) {
      throw new IllegalArgumentException("defaultTtl must be positive but was " + defaultTtl);
    }
  }

  /**
   * The configured default / maximum TTL.
   *
   * @return the strictly positive default TTL this cache was constructed with
   */
  public Duration defaultTtl() {
    return defaultTtl;
  }

  /**
   * Renders the Redis key of a short code: {@code shortener:url:{short_code}}.
   *
   * @param shortCode the short code (generated base62 code or custom alias)
   * @return {@link #KEY_PREFIX} followed by the short code
   * @throws NullPointerException when {@code shortCode} is {@code null}
   */
  public static String keyOf(String shortCode) {
    return KEY_PREFIX + Objects.requireNonNull(shortCode, "shortCode");
  }

  /**
   * TTL an entry receives for the given expiry: the remaining time to {@code expiresAt}, capped by
   * the default TTL; the default TTL when {@code expiresAt} is {@code null}; {@link Duration#ZERO}
   * when the mapping is already expired (such an entry must not be written).
   *
   * <p>Pure function of its arguments; exposed so the bound can be tested without Redis.
   *
   * @param expiresAt expiry instant of the mapping, or {@code null} when it never expires
   * @param now the reference instant
   * @return a duration in {@code [0, defaultTtl]}; exactly {@code defaultTtl} when the remaining
   *     time equals or exceeds it
   * @throws NullPointerException when {@code now} is {@code null}
   */
  public Duration ttlFor(Instant expiresAt, Instant now) {
    Objects.requireNonNull(now, "now");
    if (expiresAt == null) {
      return defaultTtl;
    }
    Duration remaining = Duration.between(now, expiresAt);
    // A mapping expiring exactly now is already expired (UrlMapping.isExpired uses !isAfter), so
    // zero remaining time is treated the same as negative: do not cache.
    if (remaining.isZero() || remaining.isNegative()) {
      return Duration.ZERO;
    }
    return remaining.compareTo(defaultTtl) < 0 ? remaining : defaultTtl;
  }

  /**
   * Looks up the long URL cached for a short code with a single Redis {@code GET}.
   *
   * <p>Degradation (AC-13): any {@link RuntimeException} raised by the template, whether a
   * connection failure, a command timeout or a failure obtaining the value operations, is logged
   * via {@link #degraded} and answered as a miss, so the caller falls through to Postgres.
   *
   * @param shortCode the short code
   * @return the cached long URL, or empty on a miss or when Redis is unavailable
   * @throws NullPointerException when {@code shortCode} is {@code null}
   */
  @Override
  public Optional<String> get(String shortCode) {
    String key = keyOf(shortCode);
    try {
      return Optional.ofNullable(redis.opsForValue().get(key));
    } catch (RuntimeException e) {
      // Deliberately broad: Spring Data Redis wraps driver errors in DataAccessException
      // subclasses, and a misconfigured template can fail before the command is even sent. None of
      // them may escape the boundary class.
      degraded("GET", key, e);
      return Optional.empty();
    }
  }

  /**
   * Caches the long URL of a short code with a single Redis {@code SET key value PX ttl}.
   *
   * <p>The TTL is {@link #ttlFor}{@code (expiresAt, clock.instant())}. When that is zero the
   * mapping is already expired and nothing is written (an expired link must never be served from
   * the cache). Any {@link RuntimeException} from Redis is logged via {@link #degraded} and
   * swallowed: the redirect has already been resolved from Postgres by the caller and is still
   * served (AC-13).
   *
   * @param shortCode the short code
   * @param longUrl the long URL to cache; stored verbatim
   * @param expiresAt expiry instant of the mapping, or {@code null} when it never expires
   * @throws NullPointerException when {@code shortCode} or {@code longUrl} is {@code null}
   */
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

  /**
   * Logs a cache degradation without the exception message (it may carry connection details).
   *
   * <p>The WARN line is the operator signal documented in {@code docs/operations.md} ("Redis cache
   * degraded: ..."); it carries the command, the key and the exception's simple class name only.
   * The full exception including its message and stack trace is available at DEBUG.
   *
   * @param command the Redis command that failed, {@code "GET"} or {@code "SET"}
   * @param key the cache key the command targeted
   * @param e the failure; only its class name reaches the WARN level
   */
  private static void degraded(String command, String key, RuntimeException e) {
    log.warn(
        "Redis cache degraded: {} {} failed with {}; serving redirect from database",
        command,
        key,
        e.getClass().getSimpleName());
    log.debug("Redis cache failure detail for {} {}", command, key, e);
  }
}
