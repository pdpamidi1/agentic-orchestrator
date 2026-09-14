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

/** Base62 encoding used for generated short codes (AC-10, AMB-11). */
class Base62CodecTest {

  private static final Pattern ALPHABET_PATTERN = Pattern.compile("^[0-9a-zA-Z]+$");

  // --- alphabet --------------------------------------------------------------------------------

  @Test
  void alphabetIsDigitsThenLowerThenUpperWithoutDuplicates() {
    assertThat(Base62Codec.ALPHABET)
        .hasSize(62)
        .isEqualTo("0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ");
    assertThat(Base62Codec.ALPHABET.chars().distinct().count()).isEqualTo(62L);
    assertThat(Base62Codec.RADIX).isEqualTo(62);
    assertThat(Base62Codec.MIN_OFFSET_FOR_THREE_CHARS).isEqualTo(3844L);
  }

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

  @Test
  void roundTripsSmallValuesExhaustively() {
    for (long value = 0; value < 62L * 62 * 62 + 62; value++) {
      assertThat(Base62Codec.decode(Base62Codec.encode(value))).isEqualTo(value);
    }
  }

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

  @ParameterizedTest
  @ValueSource(longs = {-1L, -62L, Long.MIN_VALUE})
  void rejectsNegativeValues(long value) {
    assertThatIllegalArgumentException().isThrownBy(() -> Base62Codec.encode(value));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "abc-", "ab_c", "ä", "ab c", "AB/C", "a:b"})
  void rejectsCodesOutsideTheAlphabet(String code) {
    assertThatIllegalArgumentException().isThrownBy(() -> Base62Codec.decode(code));
    assertThat(Base62Codec.isValid(code)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"aZl8N0y58M8", "zzzzzzzzzzzz", "100000000000"})
  void rejectsWellFormedCodesThatOverflowALong(String code) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Base62Codec.decode(code))
        .withMessageContaining("overflows");
    // Alphabet-wise the code is fine; only its magnitude is not representable.
    assertThat(Base62Codec.isValid(code)).isTrue();
  }

  @Test
  void rejectsNullCode() {
    assertThatNullPointerException().isThrownBy(() -> Base62Codec.decode(null));
    assertThat(Base62Codec.isValid(null)).isFalse();
  }

  // --- minimum length after seed offset (AMB-11) -----------------------------------------------

  @Test
  void smallestOffsetGuaranteesThreeCharactersForEveryCounterValue() {
    long offset = Base62Codec.MIN_OFFSET_FOR_THREE_CHARS;
    for (long raw = 0; raw < 100_000; raw++) {
      assertThat(Base62Codec.encode(raw + offset)).hasSizeGreaterThanOrEqualTo(3);
    }
    // One below the offset is still two characters: the bound is tight.
    assertThat(Base62Codec.encode(offset - 1)).hasSize(2);
  }

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

  @Test
  void dbSequenceSeedAloneAlreadyYieldsThreeCharacters() {
    // V1 migration seeds url_code_seq at 62^3 = 238328; even without the allocator's offset a
    // raw sequence value encodes to at least four characters, comfortably above the minimum.
    assertThat(Base62Codec.encode(238_328L)).isEqualTo("1000").hasSizeGreaterThanOrEqualTo(3);
  }
}
