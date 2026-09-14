package com.example.shortener.read;

import java.time.Instant;
import java.util.Optional;

/**
 * Read-through cache of {@code short_code -> long_url} lookups in front of the {@code urls} table
 * (AC-12, AC-13).
 *
 * <p>Implementations are <em>best effort</em>: a failing backend must never surface to callers.
 * {@link #get} answers {@link Optional#empty()} and {@link #put} silently does nothing when the
 * cache is unavailable, so the caller falls back to the database. All other code talks to this
 * interface; only {@link RedisUrlCache} may reference Spring Data Redis types.
 */
public interface UrlCache {

  /**
   * Looks up the long URL cached for a short code.
   *
   * @param shortCode the short code
   * @return the cached long URL, or empty on a miss <em>or</em> when the cache is unavailable
   */
  Optional<String> get(String shortCode);

  /**
   * Caches the long URL of a short code until it expires.
   *
   * <p>The entry lives for the remaining time to {@code expiresAt}, capped by the configured
   * default TTL; when {@code expiresAt} is {@code null} the default TTL applies (AMB-9). A mapping
   * that is already expired is never cached.
   *
   * @param shortCode the short code
   * @param longUrl the long URL to cache
   * @param expiresAt expiry instant of the mapping, or {@code null} when it never expires
   */
  void put(String shortCode, String longUrl, Instant expiresAt);
}
