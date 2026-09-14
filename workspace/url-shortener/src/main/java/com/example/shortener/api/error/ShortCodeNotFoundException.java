package com.example.shortener.api.error;

import java.util.Objects;

/**
 * Thrown when a short code exists in neither the cache nor the database. Rendered as {@code 404 Not
 * Found} by {@link GlobalExceptionHandler}.
 *
 * <p>The message is client-facing and must not contain internal details.
 */
public class ShortCodeNotFoundException extends RuntimeException {

  private final String shortCode;

  /**
   * @param shortCode the unknown short code
   */
  public ShortCodeNotFoundException(String shortCode) {
    super("Short code '" + Objects.requireNonNull(shortCode, "shortCode") + "' was not found");
    this.shortCode = shortCode;
  }

  /** The short code that could not be resolved. */
  public String getShortCode() {
    return shortCode;
  }
}
