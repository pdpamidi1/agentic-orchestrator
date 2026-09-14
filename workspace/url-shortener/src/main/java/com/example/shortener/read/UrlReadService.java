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
 */
@Service
public class UrlReadService {

  private static final Logger log = LoggerFactory.getLogger(UrlReadService.class);

  private final UrlCache cache;
  private final UrlMappingRepository repository;
  private final Clock clock;

  /** Creates the service with the system UTC clock. */
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
   */
  public UrlReadService(UrlCache cache, UrlMappingRepository repository, Clock clock) {
    this.cache = Objects.requireNonNull(cache, "cache");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Resolves a short code to the long URL it redirects to.
   *
   * @param shortCode the short code taken from the request path
   * @return the stored {@code long_url}
   * @throws ShortCodeNotFoundException when the code is known to neither cache nor database (404)
   * @throws ShortCodeExpiredException when the mapping's {@code expires_at} has passed (410)
   */
  public String resolve(String shortCode) {
    Objects.requireNonNull(shortCode, "shortCode");
    Optional<String> cached = cache.get(shortCode);
    if (cached.isPresent()) {
      log.debug("Cache hit for short code '{}'", shortCode);
      return cached.get();
    }

    UrlMapping mapping =
        repository
            .findByShortCode(shortCode)
            .orElseThrow(() -> new ShortCodeNotFoundException(shortCode));
    Instant now = clock.instant();
    if (mapping.isExpired(now)) {
      log.debug("Short code '{}' expired at {}", shortCode, mapping.getExpiresAt());
      throw new ShortCodeExpiredException(shortCode, mapping.getExpiresAt());
    }

    cache.put(shortCode, mapping.getLongUrl(), mapping.getExpiresAt());
    log.debug("Cache miss for short code '{}'; loaded from database", shortCode);
    return mapping.getLongUrl();
  }
}
