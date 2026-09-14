/*
 * CounterSource.java — Abstraction over a monotonically increasing raw counter
 *
 * Layer: codegen. The seam that lets ShortCodeAllocator treat the Redis batch counter and the
 * PostgreSQL sequence uniformly and swap them in tests. Implemented by RedisBatchCounterSource
 * (primary) and DbSequenceCounterSource (fallback). The ArchitectureTest rule "all other code
 * talks to the CounterSource interface" rests on this type (AC-10, AC-11, ADR-002, ADR-004).
 */
package com.example.shortener.codegen;

import com.example.shortener.domain.CodeSource;

/**
 * A monotonically increasing source of raw counter values used to derive short codes.
 *
 * <p>Implementations must never hand out the same value twice for the lifetime of the backing
 * store. Gaps are acceptable (AMB-10). Infrastructure failures are reported by throwing an
 * unchecked exception; the decision whether to fall back to another source belongs to {@link
 * ShortCodeAllocator}, not to the source.
 *
 * <p><b>Contract for implementors.</b>
 *
 * <ul>
 *   <li>{@link #next()} returns a value {@code >= 0} that is strictly greater than every value the
 *       same backing store has returned before, across process restarts and instances.
 *   <li>{@link #next()} must be safe to call concurrently from request threads.
 *   <li>{@link #next()} must throw (any {@code RuntimeException}) rather than block indefinitely or
 *       return a guessed value when the store is unavailable; the allocator relies on the exception
 *       to trigger the fallback.
 *   <li>{@link #codeSource()} is constant for the lifetime of the instance.
 * </ul>
 *
 * <p><b>Design choice.</b> The interface exposes raw integers rather than codes so that the seed
 * offset and base62 encoding are applied in exactly one place (the allocator) and both sources
 * produce codes of identical shape.
 */
public interface CounterSource {

  /**
   * Returns the next raw counter value.
   *
   * <p>Side effects depend on the implementation: at most one Redis {@code INCRBY} per batch for
   * the primary source, exactly one {@code nextval} round trip for the fallback.
   *
   * @return a value strictly greater than every value previously returned by this source
   * @throws RuntimeException when the backing store is unavailable or times out
   */
  long next();

  /**
   * Which {@link CodeSource} a code derived from this source is attributed to.
   *
   * @return the constant wire-level origin recorded in {@code urls.code_source}
   */
  CodeSource codeSource();
}
