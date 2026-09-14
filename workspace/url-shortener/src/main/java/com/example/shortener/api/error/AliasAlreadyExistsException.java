/*
 * AliasAlreadyExistsException.java — Domain exception for a taken custom alias / short code (409)
 *
 * Layer: api. One of the four application exceptions that GlobalExceptionHandler maps to an RFC
 * 9457 problem+json response. Thrown by UrlWriteService both from its repository pre-check and
 * when a concurrent insert loses the primary-key race on urls.short_code. Serves AC-3 ("existing
 * mapping unchanged") and the 409 row of createShortUrl in openapi.yaml.
 */
package com.example.shortener.api.error;

import java.util.Objects;

/**
 * Thrown when a custom alias / short code is already present in the {@code urls} table. Rendered as
 * {@code 409 Conflict} by {@link GlobalExceptionHandler}; the existing mapping is left unchanged.
 *
 * <p>The message is client-facing and must not contain internal details.
 *
 * <p><b>Responsibility.</b> Signal a uniqueness conflict on the short code without deciding how it
 * is rendered; the handler owns status, title and problem {@code type}. It is used for both
 * caller-chosen aliases and, in the rare race described in {@code docs/operations.md} 2.3, for
 * generated codes, which is why the wording says "alias" for either.
 *
 * <p><b>Invariants.</b> {@link #getAlias()} is never {@code null}; the message always has the form
 * {@code Alias '<alias>' is already taken}.
 *
 * <p><b>Thread-safety and lifecycle.</b> Immutable after construction; instantiated per failed
 * request, never a Spring bean.
 *
 * <p><b>Design choice.</b> An unchecked exception keeps the service and controller signatures free
 * of error-transport concerns and lets the architecture rule "no controller builds an error body"
 * hold: only the advice class translates it (AC-16).
 */
public class AliasAlreadyExistsException extends RuntimeException {

  /** The conflicting short code exactly as the client supplied or the allocator generated it. */
  private final String alias;

  /**
   * Creates the exception for a taken alias or short code.
   *
   * <p>The message is built eagerly so that {@link #getMessage()} is safe to forward as the problem
   * {@code detail}.
   *
   * @param alias the alias or short code that is already taken
   * @throws NullPointerException when {@code alias} is {@code null}
   */
  public AliasAlreadyExistsException(String alias) {
    super("Alias '" + Objects.requireNonNull(alias, "alias") + "' is already taken");
    this.alias = alias;
  }

  /**
   * The alias or short code that is already taken.
   *
   * @return the conflicting short code, never {@code null}
   */
  public String getAlias() {
    return alias;
  }
}
