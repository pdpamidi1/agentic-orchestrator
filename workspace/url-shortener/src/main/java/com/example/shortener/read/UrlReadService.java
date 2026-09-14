/*
 * UrlReadService.java — resolves a short code to its long URL (cache first, then Postgres)
 *
 * Layer: read. The application service behind GET /{short_code}: consults UrlCache, falls back to
 * UrlMappingRepository, decides "unknown" (ShortCodeNotFoundException -> 404, AC-8) and "expired"
 * (ShortCodeExpiredException -> 410, AC-9) against an injected Clock, and populates the cache with
 * live mappings only (AC-7, AC-12). It never catches infrastructure exceptions: Redis degradation
 * is fully handled inside RedisUrlCache, which is what makes the redirect path survive a Redis
 * outage (AC-13). Framework-web-free by design; only RedirectController carries web annotations.
 */
package com.example.shortener.read;

import com.example.shortener.api.error.ShortCodeExpiredException;
import com.example.shortener.api.error.ShortCodeNotFoundException;
import com.example.shortener.domain.UrlMapping;
import com.example.shortener.domain.UrlMappingRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Read path of the shortener: resolves a short code to the long URL to redirect to (AC-7, AC-8,
 * AC-9, AC-12, AC-13).
 *
 * <ol>
 *   <li>The {@link UrlCache} is consulted first; on a hit the long URL is returned and Postgres is
 *       not queried at all.
 *   <li>On a miss the mapping is loaded from {@link UrlMappingRepository}. An unknown code raises
 *       {@link ShortCodeNotFoundException} (404); a mapping whose {@code expires_at} is not after
 *       "now" raises {@link ShortCodeExpiredException} (410) and is never cached.
 *   <li>A live mapping is written to the cache with a TTL bounded by its {@code expires_at} (the
 *       cache computes {@code min(remaining, default 24h)}, AMB-9) and its long URL is returned.
 * </ol>
 *
 * <p>Cache unavailability is handled inside the cache implementation, which answers a miss / no-op;
 * this service therefore serves from Postgres whenever Redis is down without catching
 * infrastructure exceptions itself (AC-13). The single repository call runs in its own read
 * transaction, so a cache hit borrows no database connection.
 *
 * <p>This class carries no web annotations; every non-2xx outcome is expressed as an exception that
 * {@code GlobalExceptionHandler} renders as {@code application/problem+json}.
 *
 * <p>Design notes:
 *
 * <ul>
 *   <li>Why the cache holds only the long URL: a cache hit skips the expiry check on purpose. The
 *       entry's TTL is already bounded by {@code expires_at} (see {@link RedisUrlCache#ttlFor}), so
 *       a hit can only happen for a still-live mapping and no second clock comparison is needed.
 *   <li>Why unknown and expired codes are not cached (no negative caching): a link created on the
 *       write surface right after a failed lookup must be resolvable on the next request, and a 410
 *       decided against Postgres stays authoritative.
 *   <li>Why a separate {@link Clock}: expiry is a boundary condition ("exactly now" is expired), so
 *       tests fix the clock instead of sleeping.
 * </ul>
 *
 * <p>Profile gating: not gated itself; it is instantiated on every surface but only exercised by
 * {@code RedirectController}, which is absent under the {@code write} profile (AC-14).
 *
 * <p>Thread-safety: immutable after construction; the injected cache, repository and clock are
 * thread-safe, so the singleton is shared by all request threads.
 */
@Service
public class UrlReadService {

  /** DEBUG-only tracing of hits, misses and expiries; nothing here is a client-facing signal. */
  private static final Logger log = LoggerFactory.getLogger(UrlReadService.class);

  /** Best-effort cache consulted first; answers a miss when Redis is unavailable (AC-13). */
  private final UrlCache cache;

  /** System of record for mappings; queried only on a cache miss. */
  private final UrlMappingRepository repository;

  /** Source of "now" for the expiry decision; fixed in tests, UTC system clock live. */
  private final Clock clock;

  /**
   * Creates the service with the system UTC clock.
   *
   * <p>This is the constructor Spring uses ({@link Autowired}); it delegates to the explicit one.
   *
   * @param cache read-through cache consulted before the database
   * @param repository the {@code urls} repository
   * @throws NullPointerException when either collaborator is {@code null}
   */
  @Autowired
  public UrlReadService(UrlCache cache, UrlMappingRepository repository) {
    this(cache, repository, Clock.systemUTC());
  }

  /**
   * Creates the service with an explicit clock.
   *
   * @param cache read-through cache consulted before the database
   * @param repository the {@code urls} repository
   * @param clock source of the "now" used to decide whether a mapping has expired
   * @throws NullPointerException when any argument is {@code null}
   */
  public UrlReadService(UrlCache cache, UrlMappingRepository repository, Clock clock) {
    this.cache = Objects.requireNonNull(cache, "cache");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Resolves a short code to the long URL it redirects to.
   *
   * <p>Order of operations: cache {@code get}; on a miss, repository lookup; unknown raises 404;
   * expiry check against {@code clock.instant()} (a mapping expiring exactly now is expired);
   * expired raises 410 and is not cached; otherwise the mapping is written to the cache with its
   * {@code expires_at} (the cache bounds the TTL) and the long URL is returned.
   *
   * <p>Side effects: at most one cache write per miss. A Redis outage changes latency and log
   * output only, never the result (AC-13).
   *
   * @param shortCode the short code taken from the request path
   * @return the stored {@code long_url}
   * @throws ShortCodeNotFoundException when the code is known to neither cache nor database (404)
   * @throws ShortCodeExpiredException when the mapping's {@code expires_at} has passed (410)
   * @throws NullPointerException when {@code shortCode} is {@code null}
   */
  public String resolve(String shortCode) {
    Objects.requireNonNull(shortCode, "shortCode");
    Optional<String> cached = cache.get(shortCode);
    if (cached.isPresent()) {
      // No expiry check on a hit: the cache TTL never outlives expires_at, so a present entry
      // belongs to a live mapping (AC-12).
      log.debug("Cache hit for short code '{}'", shortCode);
      return cached.get();
    }

    UrlMapping mapping =
        repository
            .findByShortCode(shortCode)
            .orElseThrow(() -> new ShortCodeNotFoundException(shortCode));
    Instant now = clock.instant();
    if (mapping.isExpired(now)) {
      // Expired mappings are never written to the cache; the 410 is always decided here against
      // the database row (AC-9).
      log.debug("Short code '{}' expired at {}", shortCode, mapping.getExpiresAt());
      throw new ShortCodeExpiredException(shortCode, mapping.getExpiresAt());
    }

    // Cache-aside population; the cache computes min(expires_at - now, default TTL) itself and
    // silently skips the write when Redis is unavailable.
    cache.put(shortCode, mapping.getLongUrl(), mapping.getExpiresAt());
    log.debug("Cache miss for short code '{}'; loaded from database", shortCode);
    return mapping.getLongUrl();
  }
}
