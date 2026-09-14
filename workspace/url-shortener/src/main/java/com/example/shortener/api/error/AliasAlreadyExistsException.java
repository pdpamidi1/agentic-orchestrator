package com.example.shortener.api.error;

import java.util.Objects;

/**
 * Thrown when a custom alias / short code is already present in the {@code urls} table. Rendered as
 * {@code 409 Conflict} by {@link GlobalExceptionHandler}; the existing mapping is left unchanged.
 *
 * <p>The message is client-facing and must not contain internal details.
 */
public class AliasAlreadyExistsException extends RuntimeException {

  private final String alias;

  /**
   * @param alias the alias or short code that is already taken
   */
  public AliasAlreadyExistsException(String alias) {
    super("Alias '" + Objects.requireNonNull(alias, "alias") + "' is already taken");
    this.alias = alias;
  }

  /** The alias or short code that is already taken. */
  public String getAlias() {
    return alias;
  }
}
