package com.example.shortener.domain;

import java.util.Objects;

/**
 * Which counter produced a short code. The wire value is what is persisted in {@code
 * urls.code_source} (constrained by {@code urls_code_source_chk}) and exposed in API responses.
 */
public enum CodeSource {
  /** Code allocated from the batched Redis {@code INCRBY} counter. */
  REDIS("redis"),

  /** Code allocated from the Postgres fallback sequence {@code url_code_seq} (AC-11). */
  DB_SEQUENCE("db_sequence");

  private final String wireValue;

  CodeSource(String wireValue) {
    this.wireValue = wireValue;
  }

  /** The persisted / serialised value, e.g. {@code "redis"}. */
  public String wireValue() {
    return wireValue;
  }

  /**
   * Resolves an enum constant from its wire value.
   *
   * @param wireValue persisted value such as {@code "db_sequence"}
   * @return the matching constant
   * @throws IllegalArgumentException when no constant carries that wire value
   */
  public static CodeSource fromWire(String wireValue) {
    Objects.requireNonNull(wireValue, "wireValue");
    for (CodeSource source : values()) {
      if (source.wireValue.equals(wireValue)) {
        return source;
      }
    }
    throw new IllegalArgumentException("Unknown code_source wire value: " + wireValue);
  }
}
