/*
 * UrlCache.java — read-side cache abstraction (short_code -> long_url)
 *
 * Layer: read. The seam between UrlReadService and the Redis technology: the service only sees
 * this interface, RedisUrlCache is the single implementation allowed to import Spring Data Redis
 * (layering rule enforced by sdlc.ArchitectureTest). The contract deliberately makes "cache
 * unavailable" indistinguishable from "cache miss", which is what lets a Redis outage degrade to
 * plain database lookups instead of failing redirects (AC-12, AC-13). Tests substitute a Mockito
 * mock for this interface to pin the service logic without Redis.
 */
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
 *
 * <p>Design: this is a cache-aside (lazy population) cache. Nothing is written on the write
 * surface; the first redirect after a link is created misses, loads the row from Postgres and
 * populates the entry. Because both surfaces share the same Postgres and Redis, a freshly created
 * link is visible to every read instance immediately, with no replication lag. Only positive
 * results are cached (no negative caching of unknown codes), so a code created a moment after a
 * failed lookup is found on the very next request.
 *
 * <p>Invariants that every implementation must keep:
 *
 * <ul>
 *   <li>An entry never outlives the mapping: the TTL is bounded by {@code expires_at}, so an
 *       expired link is never served from the cache and a 410 is always decided against Postgres.
 *   <li>A mapping that is already expired is never written.
 *   <li>Neither method throws for infrastructure reasons; failures are logged by the implementation
 *       and mapped to a miss / no-op.
 * </ul>
 *
 * <p>Thread-safety: implementations must be safe for concurrent use from the servlet thread pool;
 * they are singletons shared by every request of the read surface.
 */
public interface UrlCache {

  /**
   * Looks up the long URL cached for a short code.
   *
   * <p>Side effects: none on the cache content. An implementation may log a degradation warning
   * when its backend fails, but it never propagates the failure.
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
   * <p>Side effects: one write to the cache backend with a TTL; nothing when the mapping is expired
   * or the backend is unavailable (best effort, never throws for infrastructure reasons).
   *
   * @param shortCode the short code
   * @param longUrl the long URL to cache
   * @param expiresAt expiry instant of the mapping, or {@code null} when it never expires
   */
  void put(String shortCode, String longUrl, Instant expiresAt);
}
