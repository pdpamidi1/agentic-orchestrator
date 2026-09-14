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
 */
public final class Base62Codec {

  /** The 62-symbol alphabet; the index of a character is its digit value. */
  public static final String ALPHABET =
      "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

  /** The radix, {@code 62}. */
  public static final int RADIX = ALPHABET.length();

  /** {@code 62^2}: the smallest value whose base62 encoding is three characters long. */
  public static final long MIN_OFFSET_FOR_THREE_CHARS = (long) RADIX * RADIX;

  private static final int[] DIGIT_VALUES = buildDigitValues();

  private Base62Codec() {}

  /**
   * Encodes a non-negative value.
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
    // A long never needs more than 11 base62 digits.
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
   * @param code non-empty string over {@code [0-9a-zA-Z]}
   * @return the decoded value
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
   */
  public static boolean isValid(String code) {
    if (code == null || code.isEmpty()) {
      return false;
    }
    for (int i = 0; i < code.length(); i++) {
      char c = code.charAt(i);
      if (c >= DIGIT_VALUES.length || DIGIT_VALUES[c] < 0) {
        return false;
      }
    }
    return true;
  }

  private static int digitValue(char c) {
    int digit = c < DIGIT_VALUES.length ? DIGIT_VALUES[c] : -1;
    if (digit < 0) {
      throw new IllegalArgumentException("character outside the base62 alphabet: '" + c + "'");
    }
    return digit;
  }

  private static int[] buildDigitValues() {
    int[] table = new int[128];
    java.util.Arrays.fill(table, -1);
    for (int i = 0; i < ALPHABET.length(); i++) {
      table[ALPHABET.charAt(i)] = i;
    }
    return table;
  }
}
