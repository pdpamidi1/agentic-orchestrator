/*
 * ShortCodeNotFoundException.java — Domain exception for an unknown short code (404)
 *
 * Layer: api. Thrown by UrlReadService when the Redis cache misses (or is degraded) and
 * UrlMappingRepository.findByShortCode returns empty. GlobalExceptionHandler maps it to 404
 * problem+json. Serves AC-8 and the 404 row of redirectToLongUrl in openapi.yaml. Routes that are
 * not registered on a profile-restricted surface also answer 404, but through Spring MVC's own
 * NoResourceFoundException, not through this class.
 */
package com.example.shortener.api.error;

import java.util.Objects;

/**
 * Thrown when a short code exists in neither the cache nor the database. Rendered as {@code 404 Not
 * Found} by {@link GlobalExceptionHandler}.
 *
 * <p>The message is client-facing and must not contain internal details.
 *
 * <p><b>Responsibility.</b> Report a lookup miss on the system of record. Because the cache is
 * read-through and never authoritative, a cache miss alone never raises this exception; only the
 * database answer does.
 *
 * <p><b>Invariants.</b> {@link #getShortCode()} is never {@code null}; the message always has the
 * form {@code Short code '<code>' was not found}. The echoed value is the raw path segment, which
 * may be any string the client sent.
 *
 * <p><b>Thread-safety and lifecycle.</b> Immutable; created per failed redirect, not a bean.
 */
public class ShortCodeNotFoundException extends RuntimeException {

  /** The short code taken from the request path; never {@code null}. */
  private final String shortCode;

  /**
   * Creates the exception for an unknown short code.
   *
   * @param shortCode the unknown short code
   * @throws NullPointerException when {@code shortCode} is {@code null}
   */
  public ShortCodeNotFoundException(String shortCode) {
    super("Short code '" + Objects.requireNonNull(shortCode, "shortCode") + "' was not found");
    this.shortCode = shortCode;
  }

  /**
   * The short code that could not be resolved.
   *
   * @return the short code, never {@code null}
   */
  public String getShortCode() {
    return shortCode;
  }
}
