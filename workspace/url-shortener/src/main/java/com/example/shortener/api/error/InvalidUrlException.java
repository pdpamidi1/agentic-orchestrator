package com.example.shortener.api.error;

import java.util.Objects;

/**
 * Thrown for semantic URL or expiry rejections that are detected outside Bean Validation (for
 * example by a service that inspects the parsed URL). Rendered as {@code 400 Bad Request} by {@link
 * GlobalExceptionHandler}.
 *
 * <p>The message is client-facing and becomes the problem {@code detail}; it must describe the
 * rejected input rule, never internal state.
 */
public class InvalidUrlException extends RuntimeException {

  /**
   * @param message client-facing description of why the URL or expiry was rejected
   */
  public InvalidUrlException(String message) {
    super(Objects.requireNonNull(message, "message"));
  }

  /**
   * @param message client-facing description of why the URL or expiry was rejected
   * @param cause the underlying parse failure; never exposed to the client
   */
  public InvalidUrlException(String message, Throwable cause) {
    super(Objects.requireNonNull(message, "message"), cause);
  }
}
