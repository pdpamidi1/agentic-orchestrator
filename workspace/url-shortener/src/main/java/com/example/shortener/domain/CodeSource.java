/*
 * CodeSource.java — Enum naming the counter that produced a short code (redis | db_sequence)
 *
 * Layer: domain. Shared vocabulary between the codegen layer (each CounterSource declares one),
 * the entity (persisted through UrlMapping.CodeSourceConverter into urls.code_source) and the API
 * (its wire value is echoed as code_source in CreateUrlResponse). The two wire values are pinned
 * by the urls_code_source_chk check constraint of the V1 migration. Serves AC-11 and the README
 * section "code_source semantics".
 */
package com.example.shortener.domain;

import java.util.Objects;

/**
 * Which counter produced a short code. The wire value is what is persisted in {@code
 * urls.code_source} (constrained by {@code urls_code_source_chk}) and exposed in API responses.
 *
 * <p><b>Responsibility.</b> Decouple the Java constant names from the persisted / serialised
 * strings. Code must use {@link #wireValue()} and {@link #fromWire(String)} at every storage or API
 * boundary and never {@code name()} / {@code valueOf()}, so that renaming a constant cannot
 * silently change the database or the contract.
 *
 * <p><b>Invariants.</b> Wire values are lower-case snake_case, unique, and exactly the set allowed
 * by the database check constraint ({@code UrlMappingTest} asserts both facts). Lookup is
 * case-sensitive: {@code "REDIS"} is rejected.
 *
 * <p><b>Thread-safety and lifecycle.</b> Enum constants are immutable singletons.
 *
 * <p><b>Semantics.</b> {@code REDIS} is the normal path; {@code DB_SEQUENCE} marks a code issued
 * while Redis was unreachable and is a client-visible signal that the write side ran degraded. A
 * custom alias consumed no counter; the write service records {@code REDIS} for it nominally.
 */
public enum CodeSource {
  /** Code allocated from the batched Redis {@code INCRBY} counter. */
  REDIS("redis"),

  /** Code allocated from the Postgres fallback sequence {@code url_code_seq} (AC-11). */
  DB_SEQUENCE("db_sequence");

  /** The persisted and serialised spelling; fixed by the database check constraint. */
  private final String wireValue;

  /**
   * Associates a constant with its wire value.
   *
   * @param wireValue the persisted / serialised spelling
   */
  CodeSource(String wireValue) {
    this.wireValue = wireValue;
  }

  /**
   * The persisted / serialised value, e.g. {@code "redis"}.
   *
   * @return the wire value, never {@code null}
   */
  public String wireValue() {
    return wireValue;
  }

  /**
   * Resolves an enum constant from its wire value.
   *
   * <p>Linear scan over the two constants; used by the JPA converter when reading rows, so an
   * unknown value in the database surfaces as an exception rather than a {@code null} origin.
   *
   * @param wireValue persisted value such as {@code "db_sequence"}
   * @return the matching constant
   * @throws NullPointerException when {@code wireValue} is {@code null}
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
