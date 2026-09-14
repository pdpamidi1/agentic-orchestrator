/*
 * ShortCodeExpiredException.java — Domain exception for an expired mapping (410 Gone)
 *
 * Layer: api. Thrown by UrlReadService when a short code is found in PostgreSQL but
 * UrlMapping.isExpired(now) is true; such a mapping is also never written to the Redis cache.
 * GlobalExceptionHandler maps it to 410 problem+json. Serves AC-9 and the 410 row of
 * redirectToLongUrl in openapi.yaml; the distinction from 404 lets clients tell "never existed"
 * from "existed and lapsed".
 */
package com.example.shortener.api.error;

import java.time.Instant;
import java.util.Objects;

/**
 * Thrown when a short code resolves to a mapping whose {@code expires_at} lies in the past.
 * Rendered as {@code 410 Gone} by {@link GlobalExceptionHandler}.
 *
 * <p>The message is client-facing and must not contain internal details.
 *
 * <p><b>Responsibility.</b> Report that a known mapping can no longer be followed, optionally with
 * the instant at which it lapsed so the problem {@code detail} can say when.
 *
 * <p><b>Invariants.</b> {@link #getShortCode()} is never {@code null}. {@link #getExpiredAt()} may
 * be {@code null} (single-argument constructor); when present the message ends with {@code " at
 * <instant>"}. "Expired" here follows {@code UrlMapping#isExpired}: the mapping is considered gone
 * exactly at and after its expiry instant.
 *
 * <p><b>Thread-safety and lifecycle.</b> Immutable; created per rejected redirect, not a bean.
 *
 * <p><b>Design choice.</b> A dedicated exception (rather than reusing "not found") keeps the API
 * contract honest: the row still exists and is not deleted or swept by this release, so a 410 is
 * the truthful status.
 */
public class ShortCodeExpiredException extends RuntimeException {

  /** The short code taken from the request path; never {@code null}. */
  private final String shortCode;

  /** The mapping's {@code expires_at}, or {@code null} when the caller did not supply it. */
  private final Instant expiredAt;

  /**
   * Creates the exception without an expiry instant. The message then omits the "at ..." suffix.
   *
   * @param shortCode the expired short code
   * @throws NullPointerException when {@code shortCode} is {@code null}
   */
  public ShortCodeExpiredException(String shortCode) {
    this(shortCode, null);
  }

  /**
   * Creates the exception with the instant at which the mapping expired. This is the constructor
   * used on the read path, which passes {@code mapping.getExpiresAt()}.
   *
   * @param shortCode the expired short code
   * @param expiredAt the instant at which the mapping expired, or {@code null} when unknown
   * @throws NullPointerException when {@code shortCode} is {@code null}
   */
  public ShortCodeExpiredException(String shortCode, Instant expiredAt) {
    super(buildMessage(Objects.requireNonNull(shortCode, "shortCode"), expiredAt));
    this.shortCode = shortCode;
    this.expiredAt = expiredAt;
  }

  /**
   * The short code whose mapping has expired.
   *
   * @return the short code, never {@code null}
   */
  public String getShortCode() {
    return shortCode;
  }

  /**
   * The expiry instant, or {@code null} when it was not supplied.
   *
   * @return the mapping's {@code expires_at}, or {@code null}
   */
  public Instant getExpiredAt() {
    return expiredAt;
  }

  /**
   * Renders the client-facing message: {@code Short code '<code>' has expired}, followed by {@code
   * " at <instant>"} when the instant is known. The instant is printed via {@link
   * Instant#toString()}, i.e. ISO-8601 in UTC.
   *
   * @param shortCode the expired short code, already null-checked by the caller
   * @param expiredAt the expiry instant, or {@code null}
   * @return the message passed to the {@link RuntimeException} constructor
   */
  private static String buildMessage(String shortCode, Instant expiredAt) {
    String base = "Short code '" + shortCode + "' has expired";
    return expiredAt == null ? base : base + " at " + expiredAt;
  }
}
