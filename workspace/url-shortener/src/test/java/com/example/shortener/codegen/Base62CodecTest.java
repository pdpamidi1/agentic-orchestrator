/*
 * Base62CodecTest.java — base62 encode/decode used for generated short codes.
 *
 * Layer: test (unit). Pins the alphabet order ([0-9a-zA-Z], index = digit value), the exact
 * encoding of boundary values (62^k - 1 / 62^k, Long.MAX_VALUE), lossless round trips, rejection
 * of negative values, foreign characters and overflowing codes, and the minimum-length guarantee
 * obtained by adding a seed offset before encoding (AC-10, AMB-11). Technique: plain JUnit 5 with
 * @ParameterizedTest tables, exhaustive small ranges and seeded random sampling; no Spring, no
 * containers. Run with ./mvnw test.
 */
package com.example.shortener.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.example.shortener.config.ShortenerProperties;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Base62 encoding used for generated short codes (AC-10, AMB-11).
 *
 * <p>Fixture strategy: none needed. {@link Base62Codec} is a stateless utility, so every test calls
 * the static methods directly. Randomised tests use fixed seeds ({@code 62} and {@code 42}) so a
 * failure is reproducible; exhaustive loops stay below a few hundred thousand iterations to keep
 * the class in the millisecond range.
 *
 * <p>Removing this class would leave the code alphabet and the encoding unpinned: reordering the
 * alphabet, changing the radix or breaking overflow detection would still pass the allocator tests
 * (which only check decode(encode(x)) consistency) yet silently change or collide every short code
 * already stored in production.
 */
class Base62CodecTest {

  /** The only characters a generated code may contain; also the DB check on {@code short_code}. */
  private static final Pattern ALPHABET_PATTERN = Pattern.compile("^[0-9a-zA-Z]+$");

  // --- alphabet --------------------------------------------------------------------------------

  /**
   * Given the codec constants, when inspected, then the alphabet is exactly digits, lowercase,
   * uppercase (62 distinct symbols), the radix is 62 and the three-character offset is 62^2 = 3844.
   */
  @Test
  void alphabetIsDigitsThenLowerThenUpperWithoutDuplicates() {
    assertThat(Base62Codec.ALPHABET)
        .hasSize(62)
        .isEqualTo("0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ");
    assertThat(Base62Codec.ALPHABET.chars().distinct().count()).isEqualTo(62L);
    assertThat(Base62Codec.RADIX).isEqualTo(62);
    assertThat(Base62Codec.MIN_OFFSET_FOR_THREE_CHARS).isEqualTo(3844L);
  }

  /**
   * Given a table of known value/code pairs (single digits, each 62^k - 1 / 62^k boundary up to
   * four characters, the default seed offset 62^5 = "100000" and {@code Long.MAX_VALUE}), when
   * encoded and decoded, then both directions match the table exactly.
   *
   * @param value the counter value
   * @param expected its base62 rendering
   */
  @ParameterizedTest
  @CsvSource({
    "0, 0",
    "9, 9",
    "10, a",
    "35, z",
    "36, A",
    "61, Z",
    "62, 10",
    "3843, ZZ",
    "3844, 100",
    "238327, ZZZ",
    "238328, 1000",
    "916132832, 100000",
    "9223372036854775807, aZl8N0y58M7"
  })
  void encodesKnownValues(long value, String expected) {
    assertThat(Base62Codec.encode(value)).isEqualTo(expected);
    assertThat(Base62Codec.decode(expected)).isEqualTo(value);
  }

  /**
   * Given 10,000 seeded random non-negative longs, when encoded, then every code matches the
   * alphabet pattern and {@code isValid} agrees.
   */
  @Test
  void everyEncodedCodeUsesOnlyTheAlphabet() {
    Random random = new Random(62);
    for (int i = 0; i < 10_000; i++) {
      long value = Math.abs(random.nextLong() % Long.MAX_VALUE);
      String code = Base62Codec.encode(value);
      assertThat(code).matches(ALPHABET_PATTERN);
      assertThat(Base62Codec.isValid(code)).isTrue();
    }
  }

  // --- round trip ------------------------------------------------------------------------------

  /**
   * Given every value from 0 up to just past 62^3, when encoded then decoded, then the original
   * value is recovered (covers all one-, two- and three-character codes exhaustively).
   */
  @Test
  void roundTripsSmallValuesExhaustively() {
    for (long value = 0; value < 62L * 62 * 62 + 62; value++) {
      assertThat(Base62Codec.decode(Base62Codec.encode(value))).isEqualTo(value);
    }
  }

  /**
   * Given 10,000 seeded random values plus {@code Long.MAX_VALUE} and {@code Integer.MAX_VALUE},
   * when round-tripped, then each value is recovered exactly.
   */
  @Test
  void roundTripsRandomValuesAndBoundaries() {
    Random random = new Random(42);
    for (int i = 0; i < 10_000; i++) {
      long value = Math.abs(random.nextLong() % Long.MAX_VALUE);
      assertThat(Base62Codec.decode(Base62Codec.encode(value))).isEqualTo(value);
    }
    assertThat(Base62Codec.decode(Base62Codec.encode(Long.MAX_VALUE))).isEqualTo(Long.MAX_VALUE);
    assertThat(Base62Codec.decode(Base62Codec.encode(Integer.MAX_VALUE)))
        .isEqualTo(Integer.MAX_VALUE);
  }

  /**
   * Given values 0..299,999 in order, when encoded, then the code length never shrinks (no padding,
   * no leading zeros) and ends at four characters because 300,000 lies between 62^3 and 62^4.
   */
  @Test
  void encodedLengthGrowsMonotonicallyWithTheValue() {
    long previous = 1;
    for (long value = 0; value < 300_000; value++) {
      long length = Base62Codec.encode(value).length();
      assertThat(length).isGreaterThanOrEqualTo(previous);
      previous = length;
    }
    assertThat(previous).isEqualTo(4L); // 300_000 > 62^3
  }

  // --- rejected input --------------------------------------------------------------------------

  /**
   * Given a negative value (including {@code Long.MIN_VALUE}), when encoded, then an {@link
   * IllegalArgumentException} is thrown; counters never go negative.
   *
   * @param value the rejected value
   */
  @ParameterizedTest
  @ValueSource(longs = {-1L, -62L, Long.MIN_VALUE})
  void rejectsNegativeValues(long value) {
    assertThatIllegalArgumentException().isThrownBy(() -> Base62Codec.encode(value));
  }

  /**
   * Given an empty string or a code with a character outside the alphabet (punctuation, space,
   * non-ASCII), when decoded, then it is rejected and {@code isValid} reports {@code false}.
   *
   * @param code the rejected code
   */
  @ParameterizedTest
  @ValueSource(strings = {"", "abc-", "ab_c", "ä", "ab c", "AB/C", "a:b"})
  void rejectsCodesOutsideTheAlphabet(String code) {
    assertThatIllegalArgumentException().isThrownBy(() -> Base62Codec.decode(code));
    assertThat(Base62Codec.isValid(code)).isFalse();
  }

  /**
   * Given alphabet-conforming codes whose value exceeds a {@code long} ({@code Long.MAX_VALUE + 1}
   * and two twelve-character codes), when decoded, then an overflow is reported instead of a
   * silently wrapped value, while {@code isValid} (a charset check only) still says {@code true}.
   *
   * @param code the overflowing code
   */
  @ParameterizedTest
  @ValueSource(strings = {"aZl8N0y58M8", "zzzzzzzzzzzz", "100000000000"})
  void rejectsWellFormedCodesThatOverflowALong(String code) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Base62Codec.decode(code))
        .withMessageContaining("overflows");
    // Alphabet-wise the code is fine; only its magnitude is not representable.
    assertThat(Base62Codec.isValid(code)).isTrue();
  }

  /**
   * Given {@code null}, when decoded, then a {@link NullPointerException} is thrown, and {@code
   * isValid(null)} is simply {@code false}.
   */
  @Test
  void rejectsNullCode() {
    assertThatNullPointerException().isThrownBy(() -> Base62Codec.decode(null));
    assertThat(Base62Codec.isValid(null)).isFalse();
  }

  // --- minimum length after seed offset (AMB-11) -----------------------------------------------

  /**
   * Given the smallest allowed offset (62^2), when every raw counter value from 0 to 99,999 is
   * offset and encoded, then each code has at least three characters, and the value just below the
   * offset still encodes to two: the bound is tight (AMB-11).
   */
  @Test
  void smallestOffsetGuaranteesThreeCharactersForEveryCounterValue() {
    long offset = Base62Codec.MIN_OFFSET_FOR_THREE_CHARS;
    for (long raw = 0; raw < 100_000; raw++) {
      assertThat(Base62Codec.encode(raw + offset)).hasSizeGreaterThanOrEqualTo(3);
    }
    // One below the offset is still two characters: the bound is tight.
    assertThat(Base62Codec.encode(offset - 1)).hasSize(2);
  }

  /**
   * Given the production default offset {@link ShortenerProperties#DEFAULT_COUNTER_SEED_OFFSET},
   * when checked, then it equals 62^5, encodes to "100000", and the first 100,000 counter values
   * all yield six-character codes while the value just below yields five.
   */
  @Test
  void defaultConfiguredOffsetYieldsAtLeastSixCharacters() {
    long offset = ShortenerProperties.DEFAULT_COUNTER_SEED_OFFSET;
    assertThat(offset).isEqualTo(62L * 62 * 62 * 62 * 62);
    for (long raw = 0; raw < 100_000; raw++) {
      assertThat(Base62Codec.encode(raw + offset)).hasSizeGreaterThanOrEqualTo(6);
    }
    assertThat(Base62Codec.encode(offset)).isEqualTo("100000");
    assertThat(Base62Codec.encode(offset - 1)).hasSize(5);
  }

  /**
   * Given the start value of the Postgres fallback sequence (62^3 = 238,328, see the V1 migration),
   * when encoded without any offset, then the code is "1000": already four characters, so even a
   * raw fallback value satisfies the three-character minimum.
   */
  @Test
  void dbSequenceSeedAloneAlreadyYieldsThreeCharacters() {
    // V1 migration seeds url_code_seq at 62^3 = 238328; even without the allocator's offset a
    // raw sequence value encodes to at least four characters, comfortably above the minimum.
    assertThat(Base62Codec.encode(238_328L)).isEqualTo("1000").hasSizeGreaterThanOrEqualTo(3);
  }
}
