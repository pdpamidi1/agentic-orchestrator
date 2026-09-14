/*
 * InvalidUrlException.java — Domain exception for semantically invalid input (400)
 *
 * Layer: api. Second line of defence behind the Bean Validation constraints of CreateUrlRequest:
 * UrlWriteService throws it when a long_url does not parse to an absolute http/https URI with a
 * host, is too long, when a custom_alias violates the alias pattern, or when expiration_date is
 * not after the service clock. GlobalExceptionHandler renders it as 400 problem+json with the
 * message as detail (AC-2, AC-5, AC-6; 400 row of createShortUrl in openapi.yaml).
 */
package com.example.shortener.api.error;

import java.util.Objects;

/**
 * Thrown for semantic URL or expiry rejections that are detected outside Bean Validation (for
 * example by a service that inspects the parsed URL). Rendered as {@code 400 Bad Request} by {@link
 * GlobalExceptionHandler}.
 *
 * <p>The message is client-facing and becomes the problem {@code detail}; it must describe the
 * rejected input rule, never internal state.
 *
 * <p><b>Responsibility.</b> Carry one human-readable rule violation from the service layer to the
 * error handler. Unlike the other application exceptions it has no typed accessor: the offending
 * value is deliberately not retained, because echoing an arbitrary client string back in a response
 * body is unnecessary for the client and undesirable for log hygiene.
 *
 * <p><b>Invariants.</b> The message is never {@code null}. The optional cause is retained for
 * server-side diagnostics only; {@link GlobalExceptionHandler} never serialises causes.
 *
 * <p><b>Thread-safety and lifecycle.</b> Immutable; created per rejected request, not a bean.
 *
 * <p><b>Design choice.</b> Both the DTO constraints and this exception map to 400 so that a client
 * observes the same status regardless of which layer caught the problem; only the problem {@code
 * type} differs ({@code validation-error} vs {@code invalid-url}).
 */
public class InvalidUrlException extends RuntimeException {

  /**
   * Creates the exception with a client-facing message and no cause.
   *
   * @param message client-facing description of why the URL or expiry was rejected
   * @throws NullPointerException when {@code message} is {@code null}
   */
  public InvalidUrlException(String message) {
    super(Objects.requireNonNull(message, "message"));
  }

  /**
   * Creates the exception with a client-facing message and the underlying parse failure. Used by
   * {@code UrlWriteService} when {@code new URI(longUrl)} throws {@code URISyntaxException}.
   *
   * @param message client-facing description of why the URL or expiry was rejected
   * @param cause the underlying parse failure; never exposed to the client
   * @throws NullPointerException when {@code message} is {@code null}
   */
  public InvalidUrlException(String message, Throwable cause) {
    super(Objects.requireNonNull(message, "message"), cause);
  }
}
