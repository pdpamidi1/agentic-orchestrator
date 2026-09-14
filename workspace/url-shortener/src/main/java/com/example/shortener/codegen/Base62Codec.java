/*
 * Base62Codec.java — Pure base62 encoder/decoder over [0-9a-zA-Z]
 *
 * Layer: codegen. Stateless utility used by ShortCodeAllocator to turn (counter + seed offset)
 * into a short code, and by tests to decode codes back to counter values. Has no Spring or
 * infrastructure dependency. Implements the "short codes are base62" decision (AC-10) and provides
 * the MIN_OFFSET_FOR_THREE_CHARS bound that the allocator enforces for AMB-11.
 */
package com.example.shortener.codegen;

import java.util.Objects;

/**
 * Encodes non-negative counter values as base62 strings over the alphabet {@code [0-9a-zA-Z]} and
 * decodes them back (AC-10).
 *
 * <p>The codec itself never pads: the minimum code length required by AMB-11 is obtained by adding
 * {@code shortener.counter-seed-offset} to the raw counter value <em>before</em> encoding (see
 * {@link ShortCodeAllocator}). {@link #MIN_OFFSET_FOR_THREE_CHARS} is the smallest offset that
 * guarantees three characters for every non-negative counter value.
 *
 * <p><b>Responsibility.</b> A bijection between {@code long} values {@code >= 0} and their
 * canonical base62 strings (no leading zeros), plus a cheap alphabet check.
 *
 * <p><b>Invariants.</b> {@code decode(encode(v)) == v} for every {@code v >= 0}. {@code encode} is
 * strictly monotonic in length-then-lexicographic order of the alphabet, so consecutive counter
 * values yield distinct codes. Digits are ordered {@code 0-9}, then {@code a-z}, then {@code A-Z};
 * the alphabet is case-sensitive.
 *
 * <p><b>Thread-safety and lifecycle.</b> Final class with a private constructor and only static
 * members; the lookup table is built once at class initialisation and never mutated, so all methods
 * are safe to call concurrently.
 *
 * <p><b>Design choice.</b> A counter encoded in base62 (rather than a random or hashed code) makes
 * uniqueness a property of the counter, needs no collision retry loop, and gives the shortest codes
 * for a given capacity: 62^6 six-character codes. Enumeration is made harder by the batch gaps and
 * the seed offset, not by randomness (see {@code docs/operations.md} 2).
 */
public final class Base62Codec {

  /**
   * The 62-symbol alphabet; the index of a character is its digit value. The order (digits, lower
   * case, upper case) is the conventional one and is shared with the {@code
   * urls_short_code_format_chk} database constraint and the {@code custom_alias} pattern.
   */
  public static final String ALPHABET =
      "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

  /** The radix, {@code 62}. Derived from the alphabet so the two can never disagree. */
  public static final int RADIX = ALPHABET.length();

  /**
   * {@code 62^2 = 3844}: the smallest value whose base62 encoding is three characters long. Adding
   * at least this offset to a non-negative counter guarantees codes of three or more characters
   * (AMB-11); {@link ShortCodeAllocator} rejects smaller seed offsets. The production default
   * offset is much larger ({@code 62^5}, six characters).
   */
  public static final long MIN_OFFSET_FOR_THREE_CHARS = (long) RADIX * RADIX;

  /**
   * Reverse lookup table for ASCII: {@code DIGIT_VALUES[c]} is the digit value of character {@code
   * c}, or {@code -1} when {@code c} is not in the alphabet. Sized 128 so that any character {@code
   * >= 128} is rejected by a bounds check before indexing.
   */
  private static final int[] DIGIT_VALUES = buildDigitValues();

  /** Not instantiable: all members are static. */
  private Base62Codec() {}

  /**
   * Encodes a non-negative value.
   *
   * <p>Works from the least significant digit upwards into a fixed buffer and returns the used
   * suffix; no allocation beyond the result string.
   *
   * @param value counter value, {@code >= 0}
   * @return the base62 representation without leading zeros ({@code 0} encodes as {@code "0"})
   * @throws IllegalArgumentException when {@code value} is negative
   */
  public static String encode(long value) {
    if (value < 0) {
      throw new IllegalArgumentException("value must be >= 0 but was " + value);
    }
    if (value == 0) {
      return String.valueOf(ALPHABET.charAt(0));
    }
    // A long never needs more than 11 base62 digits (62^10 < Long.MAX_VALUE < 62^11).
    char[] buffer = new char[11];
    int position = buffer.length;
    long remaining = value;
    while (remaining > 0) {
      buffer[--position] = ALPHABET.charAt((int) (remaining % RADIX));
      remaining /= RADIX;
    }
    return new String(buffer, position, buffer.length - position);
  }

  /**
   * Decodes a base62 string produced by {@link #encode(long)}.
   *
   * <p>Leading {@code '0'} characters are accepted and ignored numerically ({@code "007"} decodes
   * like {@code "7"}), so decoding is total on valid alphabets even though {@code encode} never
   * emits them. Overflow is detected with exact arithmetic rather than silently wrapping.
   *
   * @param code non-empty string over {@code [0-9a-zA-Z]}
   * @return the decoded value
   * @throws NullPointerException when {@code code} is {@code null}
   * @throws IllegalArgumentException when the string is empty, contains a character outside the
   *     alphabet, or overflows a {@code long}
   */
  public static long decode(String code) {
    Objects.requireNonNull(code, "code");
    if (code.isEmpty()) {
      throw new IllegalArgumentException("code must not be empty");
    }
    long value = 0;
    for (int i = 0; i < code.length(); i++) {
      int digit = digitValue(code.charAt(i));
      try {
        value = Math.addExact(Math.multiplyExact(value, RADIX), digit);
      } catch (ArithmeticException e) {
        throw new IllegalArgumentException("code overflows a long: " + code, e);
      }
    }
    return value;
  }

  /**
   * Whether every character of {@code code} belongs to the alphabet (and the code is non-empty).
   *
   * <p>Does not check length limits or overflow; a {@code true} result means {@link #decode} will
   * not fail on the alphabet, not that it will succeed. {@code null} yields {@code false} rather
   * than an exception so the method can be used as a predicate.
   *
   * @param code the candidate string, may be {@code null}
   * @return {@code true} when {@code code} is non-null, non-empty and entirely base62
   */
  public static boolean isValid(String code) {
    if (code == null || code.isEmpty()) {
      return false;
    }
    for (int i = 0; i < code.length(); i++) {
      char c = code.charAt(i);
      // Non-ASCII characters are outside the table and therefore outside the alphabet.
      if (c >= DIGIT_VALUES.length || DIGIT_VALUES[c] < 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * Digit value of one character.
   *
   * @param c the character to look up
   * @return its index in {@link #ALPHABET}
   * @throws IllegalArgumentException when {@code c} is not in the alphabet
   */
  private static int digitValue(char c) {
    int digit = c < DIGIT_VALUES.length ? DIGIT_VALUES[c] : -1;
    if (digit < 0) {
      throw new IllegalArgumentException("character outside the base62 alphabet: '" + c + "'");
    }
    return digit;
  }

  /**
   * Builds {@link #DIGIT_VALUES}: a 128-entry table filled with {@code -1} and then populated with
   * the index of every alphabet character. Called once during class initialisation.
   *
   * @return the populated lookup table
   */
  private static int[] buildDigitValues() {
    int[] table = new int[128];
    java.util.Arrays.fill(table, -1);
    for (int i = 0; i < ALPHABET.length(); i++) {
      table[ALPHABET.charAt(i)] = i;
    }
    return table;
  }
}
