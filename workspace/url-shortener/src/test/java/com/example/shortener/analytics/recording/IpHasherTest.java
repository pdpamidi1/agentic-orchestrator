/*
 * IpHasherTest.java — determinism, shape and salt sensitivity of the client-IP hash
 *
 * Layer: test (unit). Pins hex(SHA-256(salt + ip)) against an independently computed digest, that
 * equal inputs give equal digests and different salts / addresses give different ones, the
 * null / blank handling, the 64-lowercase-hex shape required by click_outbox_hashed_ip_chk, the
 * AnalyticsProperties constructor and that the raw address and salt never leak via toString
 * (AC-10, AC-11). No Spring context.
 */
package com.example.shortener.analytics.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.shortener.analytics.config.AnalyticsProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** Unit tests of {@link IpHasher}. */
class IpHasherTest {

  private static final String SALT = "unit-test-salt-0123456789";
  private static final String IP = "203.0.113.7";

  private final IpHasher hasher = new IpHasher(SALT);

  /** The digest is SHA-256 over salt then address, UTF-8, rendered as lowercase hex. */
  @Test
  void hashIsSha256OfSaltFollowedByAddressInLowercaseHex() throws NoSuchAlgorithmException {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    String expected =
        HexFormat.of().formatHex(digest.digest((SALT + IP).getBytes(StandardCharsets.UTF_8)));

    assertThat(hasher.hash(IP)).isEqualTo(expected).matches("^[0-9a-f]{64}$");
  }

  /** Same salt and same address always give the same digest (unique-visitor counting). */
  @Test
  void hashIsDeterministic() {
    assertThat(hasher.hash(IP)).isEqualTo(hasher.hash(IP)).isEqualTo(new IpHasher(SALT).hash(IP));
  }

  /** A different address or a different salt changes the digest. */
  @Test
  void hashDependsOnAddressAndSalt() {
    assertThat(hasher.hash(IP)).isNotEqualTo(hasher.hash("203.0.113.8"));
    assertThat(hasher.hash(IP)).isNotEqualTo(new IpHasher("another-salt-0123456789").hash(IP));
  }

  /** IPv6 and surrounding whitespace are handled; whitespace is stripped before hashing. */
  @Test
  void ipv6AndWhitespaceAreNormalised() {
    assertThat(hasher.hash("2001:db8::1")).matches("^[0-9a-f]{64}$");
    assertThat(hasher.hash("  " + IP + "\n")).isEqualTo(hasher.hash(IP));
  }

  /** Null and blank addresses hash as the empty string: still a valid, stable digest. */
  @Test
  void nullAndBlankAddressesHashAsEmptyString() {
    String ofEmpty = hasher.hash("");
    assertThat(ofEmpty).matches("^[0-9a-f]{64}$");
    assertThat(hasher.hash(null)).isEqualTo(ofEmpty);
    assertThat(hasher.hash("   ")).isEqualTo(ofEmpty);
  }

  /** The digest never contains the raw address, and toString reveals neither address nor salt. */
  @Test
  void rawAddressAndSaltNeverLeak() {
    assertThat(hasher.hash(IP)).doesNotContain(IP);
    assertThat(hasher.toString()).doesNotContain(SALT).doesNotContain(IP);
  }

  /** The Spring constructor takes the salt from AnalyticsProperties and hashes identically. */
  @Test
  void propertiesConstructorUsesConfiguredSalt() {
    AnalyticsProperties properties =
        new AnalyticsProperties(
            SALT,
            new AnalyticsProperties.Outbox(500, 100),
            new AnalyticsProperties.Retry(5, 100, 10_000, AnalyticsProperties.Jitter.FULL),
            new AnalyticsProperties.Kafka(5_000),
            new AnalyticsProperties.Retention(90));

    assertThat(new IpHasher(properties).hash(IP)).isEqualTo(hasher.hash(IP));
  }

  /** A missing or blank salt is rejected at construction. */
  @Test
  void blankOrNullSaltIsRejected() {
    assertThatThrownBy(() -> new IpHasher("  ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IpHasher((String) null)).isInstanceOf(NullPointerException.class);
  }
}
