package com.example.shortener.codegen;

import com.example.shortener.domain.CodeSource;

/**
 * A monotonically increasing source of raw counter values used to derive short codes.
 *
 * <p>Implementations must never hand out the same value twice for the lifetime of the backing
 * store. Gaps are acceptable (AMB-10). Infrastructure failures are reported by throwing an
 * unchecked exception; the decision whether to fall back to another source belongs to {@link
 * ShortCodeAllocator}, not to the source.
 */
public interface CounterSource {

  /**
   * Returns the next raw counter value.
   *
   * @return a value strictly greater than every value previously returned by this source
   * @throws RuntimeException when the backing store is unavailable or times out
   */
  long next();

  /** Which {@link CodeSource} a code derived from this source is attributed to. */
  CodeSource codeSource();
}
