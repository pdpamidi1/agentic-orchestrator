/*
 * AllocatedCode.java — Value object pairing a generated short code with its counter of origin
 *
 * Layer: codegen. The result type of ShortCodeAllocator.allocate(). UrlWriteService unpacks it
 * into the short_code primary key and the code_source column of the new urls row, and echoes the
 * wire value of codeSource in CreateUrlResponse. Exists so that "which counter produced this
 * code" travels with the code instead of being inferred later (AC-10, AC-11; README "code_source
 * semantics").
 */
package com.example.shortener.codegen;

import com.example.shortener.domain.CodeSource;
import java.util.Objects;

/**
 * A freshly allocated short code together with the counter that produced it.
 *
 * <p><b>Responsibility.</b> Immutable carrier for the two facts the write path needs from the
 * allocator: the encoded code and its {@link CodeSource}. It performs no encoding itself.
 *
 * <p><b>Invariants.</b> {@code code} is non-null and non-blank; {@code codeSource} is non-null. The
 * allocator additionally guarantees that {@code code} is a base62 string of at least three
 * characters (six with the default seed offset), but this record does not re-validate the alphabet.
 *
 * <p><b>Thread-safety and lifecycle.</b> Immutable record, created per allocation; not a bean.
 *
 * @param code base62 short code (at least three characters, AMB-11)
 * @param codeSource which counter produced the code; persisted in {@code urls.code_source}
 */
public record AllocatedCode(String code, CodeSource codeSource) {

  /**
   * Canonical constructor; rejects null or blank components.
   *
   * @throws NullPointerException when {@code code} or {@code codeSource} is {@code null}
   * @throws IllegalArgumentException when {@code code} is blank
   */
  public AllocatedCode {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(codeSource, "codeSource");
    if (code.isBlank()) {
      throw new IllegalArgumentException("code must not be blank");
    }
  }
}
