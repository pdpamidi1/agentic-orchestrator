/*
 * ReferrerHostExtractorTest.java — host-only reduction of the Referer header
 *
 * Layer: test (unit). Pins that only the host survives (scheme, port, path, query, fragment and
 * userinfo are dropped), that hosts are lower-cased, that absent / blank / garbage / host-less
 * values give null without throwing, and the column-width guard (AC-1, AC-14). No Spring context.
 */
package com.example.shortener.analytics.recording;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests of {@link ReferrerHostExtractor}. */
class ReferrerHostExtractorTest {

  private final ReferrerHostExtractor extractor = new ReferrerHostExtractor();

  /** Only the host component is returned, whatever else the URL carries. */
  @ParameterizedTest
  @CsvSource({
    "https://example.com/, example.com",
    "https://example.com, example.com",
    "http://Example.COM:8080/some/path?q=1&r=2#frag, example.com",
    "https://user:secret@news.example.org/article, news.example.org",
    "https://sub.domain.example.co.uk/x, sub.domain.example.co.uk",
    "http://192.0.2.10/path, 192.0.2.10",
    "http://[2001:db8::1]:8080/path, [2001:db8::1]",
    "'  https://example.com/padded  ', example.com"
  })
  void returnsHostComponentOnly(String referer, String expectedHost) {
    assertThat(extractor.extract(referer)).isEqualTo(expectedHost);
  }

  /** Absent or blank header: null. */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void absentOrBlankRefererIsNull(String referer) {
    assertThat(extractor.extract(referer)).isNull();
  }

  /** Garbage and host-less references never throw and give null. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "not a url",
        "http://",
        "http:///path-without-host",
        "/relative/path?x=1",
        "about:blank",
        "mailto:someone@example.com",
        "https://exa mple.com/",
        "http://[::1",
        "%%%",
        "javascript:alert(1)"
      })
  void garbageOrHostlessRefererIsNull(String referer) {
    assertThat(extractor.extract(referer)).isNull();
  }

  /** A host longer than the referrer_host column is rejected rather than truncated. */
  @Test
  void hostLongerThanColumnIsNull() {
    String label = "a".repeat(63);
    String tooLong = String.join(".", label, label, label, label, label); // 319 chars
    assertThat(extractor.extract("https://" + tooLong + "/")).isNull();
    String maxLength = String.join(".", label, label, label, "b".repeat(63)); // 255 chars
    assertThat(extractor.extract("https://" + maxLength + "/")).isEqualTo(maxLength);
  }
}
