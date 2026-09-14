package com.example.shortener.codegen;

import com.example.shortener.domain.CodeSource;
import java.util.Objects;

/**
 * A freshly allocated short code together with the counter that produced it.
 *
 * @param code base62 short code (at least three characters, AMB-11)
 * @param codeSource which counter produced the code; persisted in {@code urls.code_source}
 */
public record AllocatedCode(String code, CodeSource codeSource) {

  /** Canonical constructor; rejects null or blank components. */
  public AllocatedCode {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(codeSource, "codeSource");
    if (code.isBlank()) {
      throw new IllegalArgumentException("code must not be blank");
    }
  }
}
