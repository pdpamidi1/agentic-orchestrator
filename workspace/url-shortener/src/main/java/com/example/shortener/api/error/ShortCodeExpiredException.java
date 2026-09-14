package com.example.shortener.api.error;

import java.time.Instant;
import java.util.Objects;

/**
 * Thrown when a short code resolves to a mapping whose {@code expires_at} lies in the past.
 * Rendered as {@code 410 Gone} by {@link GlobalExceptionHandler}.
 *
 * <p>The message is client-facing and must not contain internal details.
 */
public class ShortCodeExpiredException extends RuntimeException {

  private final String shortCode;
  private final Instant expiredAt;

  /**
   * @param shortCode the expired short code
   */
  public ShortCodeExpiredException(String shortCode) {
    this(shortCode, null);
  }

  /**
   * @param shortCode the expired short code
   * @param expiredAt the instant at which the mapping expired, or {@code null} when unknown
   */
  public ShortCodeExpiredException(String shortCode, Instant expiredAt) {
    super(buildMessage(Objects.requireNonNull(shortCode, "shortCode"), expiredAt));
    this.shortCode = shortCode;
    this.expiredAt = expiredAt;
  }

  /** The short code whose mapping has expired. */
  public String getShortCode() {
    return shortCode;
  }

  /** The expiry instant, or {@code null} when it was not supplied. */
  public Instant getExpiredAt() {
    return expiredAt;
  }

  private static String buildMessage(String shortCode, Instant expiredAt) {
    String base = "Short code '" + shortCode + "' has expired";
    return expiredAt == null ? base : base + " at " + expiredAt;
  }
}
