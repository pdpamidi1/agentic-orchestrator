/*
 * UrlMappingTest.java — expiry semantics and invariants of the UrlMapping entity and CodeSource.
 *
 * Layer: test (unit). Pins the behaviour the read path relies on for 302 vs 410 decisions: a
 * mapping without expires_at never expires, one with expires_at is expired exactly at and after
 * that instant (AC-7, AC-9); plus constructor null-checks, created_by always null, identity by
 * short_code, and the CodeSource wire values ('redis' / 'db_sequence') that the migration's CHECK
 * constraint and the JPA converter depend on. Technique: plain JUnit 5 on POJOs with fixed
 * instants; no Spring, no JPA runtime, no database. Run with ./mvnw test.
 */
package com.example.shortener.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link UrlMapping} entity and the {@link CodeSource} enum (no database).
 *
 * <p>Fixture strategy: two fixed instants ({@link #CREATED}, {@link #EXPIRY}, one day apart) and
 * the {@link #mapping(Instant)} factory that builds a mapping for code {@code abc123} with a given
 * expiry. Everything is deterministic; the system clock is never consulted.
 *
 * <p>Removing this class would leave the inclusive expiry boundary ({@code expires_at == now} is
 * expired) and the wire-value contract of {@link CodeSource} unpinned; both are cheap to break and
 * would only be caught by the Docker-bound integration tests, and the boundary not even there.
 */
class UrlMappingTest {

  /** Fixed creation instant used by every mapping in this class. */
  private static final Instant CREATED = Instant.parse("2026-09-14T10:00:00Z");

  /** Fixed expiry, exactly one day after {@link #CREATED}. */
  private static final Instant EXPIRY = Instant.parse("2026-09-15T10:00:00Z");

  /**
   * Builds a mapping for code {@code abc123} created at {@link #CREATED} from the Redis counter.
   *
   * @param expiresAt the expiry instant, or {@code null} for a never-expiring link
   * @return the new entity
   */
  private static UrlMapping mapping(Instant expiresAt) {
    return new UrlMapping(
        "abc123", "https://example.com/some/path", CREATED, expiresAt, CodeSource.REDIS);
  }

  /**
   * Given a mapping with {@code expires_at = null}, when asked at the epoch, at creation and far in
   * the future, then it is never expired.
   */
  @Test
  void neverExpiredWhenExpiresAtIsNull() {
    UrlMapping mapping = mapping(null);

    assertThat(mapping.getExpiresAt()).isNull();
    assertThat(mapping.isExpired(Instant.EPOCH)).isFalse();
    assertThat(mapping.isExpired(CREATED)).isFalse();
    assertThat(mapping.isExpired(Instant.parse("2999-01-01T00:00:00Z"))).isFalse();
  }

  /**
   * Given a mapping expiring at {@link #EXPIRY}, when asked at creation and one nanosecond before
   * expiry, then it is not yet expired.
   */
  @Test
  void notExpiredBeforeExpiresAt() {
    UrlMapping mapping = mapping(EXPIRY);

    assertThat(mapping.isExpired(CREATED)).isFalse();
    assertThat(mapping.isExpired(EXPIRY.minusNanos(1))).isFalse();
  }

  /**
   * Given a mapping expiring at {@link #EXPIRY}, when asked exactly at that instant, then it is
   * expired: the boundary is inclusive (a link is gone from its expiry instant on).
   */
  @Test
  void expiredExactlyAtExpiresAt() {
    assertThat(mapping(EXPIRY).isExpired(EXPIRY)).isTrue();
  }

  /**
   * Given a mapping expiring at {@link #EXPIRY}, when asked one nanosecond and thirty days later,
   * then it is expired.
   */
  @Test
  void expiredAfterExpiresAt() {
    UrlMapping mapping = mapping(EXPIRY);

    assertThat(mapping.isExpired(EXPIRY.plusNanos(1))).isTrue();
    assertThat(mapping.isExpired(EXPIRY.plus(Duration.ofDays(30)))).isTrue();
  }

  /** Given any mapping, when {@code isExpired(null)} is called, then an NPE is thrown. */
  @Test
  void isExpiredRejectsNullReferenceInstant() {
    assertThatThrownBy(() -> mapping(EXPIRY).isExpired(null))
        .isInstanceOf(NullPointerException.class);
  }

  /**
   * Given all constructor arguments, when a mapping is built, then every getter returns what was
   * passed and {@code created_by} is {@code null} (not settable in this release).
   */
  @Test
  void constructorMapsFieldsAndLeavesCreatedByNull() {
    UrlMapping mapping =
        new UrlMapping("promo2024", "https://example.com", CREATED, EXPIRY, CodeSource.DB_SEQUENCE);

    assertThat(mapping.getShortCode()).isEqualTo("promo2024");
    assertThat(mapping.getLongUrl()).isEqualTo("https://example.com");
    assertThat(mapping.getCreatedAt()).isEqualTo(CREATED);
    assertThat(mapping.getExpiresAt()).isEqualTo(EXPIRY);
    assertThat(mapping.getCodeSource()).isEqualTo(CodeSource.DB_SEQUENCE);
    assertThat(mapping.getCreatedBy()).isNull();
  }

  /**
   * Given a {@code null} short code, long URL, creation instant or code source (the NOT NULL
   * columns), when a mapping is built, then the constructor throws an NPE; only {@code expiresAt}
   * may be {@code null}.
   */
  @Test
  void constructorRejectsMissingMandatoryColumns() {
    assertThatThrownBy(() -> new UrlMapping(null, "https://e.com", CREATED, null, CodeSource.REDIS))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new UrlMapping("abc", null, CREATED, null, CodeSource.REDIS))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new UrlMapping("abc", "https://e.com", null, null, CodeSource.REDIS))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new UrlMapping("abc", "https://e.com", CREATED, null, null))
        .isInstanceOf(NullPointerException.class);
  }

  /**
   * Given two mappings with the same short code but different URL, expiry and source, and a third
   * with another code, when compared, then equality and hash code follow the short code only (the
   * primary key), as JPA entity identity requires.
   */
  @Test
  void equalityIsBasedOnShortCode() {
    UrlMapping a = mapping(null);
    UrlMapping sameCode =
        new UrlMapping("abc123", "https://other.example", CREATED, EXPIRY, CodeSource.DB_SEQUENCE);
    UrlMapping otherCode =
        new UrlMapping("xyz789", "https://example.com/some/path", CREATED, null, CodeSource.REDIS);

    assertThat(a).isEqualTo(sameCode).hasSameHashCodeAs(sameCode).isNotEqualTo(otherCode);
  }

  /**
   * Given the {@link CodeSource} constants, when their wire values are read, then they are exactly
   * {@code redis} and {@code db_sequence} in that order: the values allowed by the migration's
   * {@code urls_code_source_chk} and exposed in API responses.
   */
  @Test
  void codeSourceWireValuesMatchTheMigrationCheckConstraint() {
    assertThat(CodeSource.REDIS.wireValue()).isEqualTo("redis");
    assertThat(CodeSource.DB_SEQUENCE.wireValue()).isEqualTo("db_sequence");
    assertThat(CodeSource.values()).containsExactly(CodeSource.REDIS, CodeSource.DB_SEQUENCE);
  }

  /**
   * Given each wire value, when resolved with {@code fromWire}, then the same constant comes back;
   * given an unknown value or the wrong case ({@code "REDIS"}), then an {@link
   * IllegalArgumentException} naming the value is thrown.
   */
  @Test
  void codeSourceFromWireRoundTripsAndRejectsUnknownValues() {
    for (CodeSource source : CodeSource.values()) {
      assertThat(CodeSource.fromWire(source.wireValue())).isSameAs(source);
    }
    assertThatThrownBy(() -> CodeSource.fromWire("snowflake"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("snowflake");
    assertThatThrownBy(() -> CodeSource.fromWire("REDIS"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * Given the JPA {@link UrlMapping.CodeSourceConverter}, when converting in both directions, then
   * constants map to their wire values and back, and {@code null} passes through unchanged.
   */
  @Test
  void codeSourceConverterPersistsWireValue() {
    UrlMapping.CodeSourceConverter converter = new UrlMapping.CodeSourceConverter();

    assertThat(converter.convertToDatabaseColumn(CodeSource.REDIS)).isEqualTo("redis");
    assertThat(converter.convertToDatabaseColumn(CodeSource.DB_SEQUENCE)).isEqualTo("db_sequence");
    assertThat(converter.convertToDatabaseColumn(null)).isNull();
    assertThat(converter.convertToEntityAttribute("db_sequence")).isEqualTo(CodeSource.DB_SEQUENCE);
    assertThat(converter.convertToEntityAttribute("redis")).isEqualTo(CodeSource.REDIS);
    assertThat(converter.convertToEntityAttribute(null)).isNull();
  }
}
