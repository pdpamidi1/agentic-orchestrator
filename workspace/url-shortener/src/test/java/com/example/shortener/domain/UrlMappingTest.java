package com.example.shortener.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit tests for the {@link UrlMapping} entity and the {@link CodeSource} enum (no database). */
class UrlMappingTest {

  private static final Instant CREATED = Instant.parse("2026-09-14T10:00:00Z");
  private static final Instant EXPIRY = Instant.parse("2026-09-15T10:00:00Z");

  private static UrlMapping mapping(Instant expiresAt) {
    return new UrlMapping(
        "abc123", "https://example.com/some/path", CREATED, expiresAt, CodeSource.REDIS);
  }

  @Test
  void neverExpiredWhenExpiresAtIsNull() {
    UrlMapping mapping = mapping(null);

    assertThat(mapping.getExpiresAt()).isNull();
    assertThat(mapping.isExpired(Instant.EPOCH)).isFalse();
    assertThat(mapping.isExpired(CREATED)).isFalse();
    assertThat(mapping.isExpired(Instant.parse("2999-01-01T00:00:00Z"))).isFalse();
  }

  @Test
  void notExpiredBeforeExpiresAt() {
    UrlMapping mapping = mapping(EXPIRY);

    assertThat(mapping.isExpired(CREATED)).isFalse();
    assertThat(mapping.isExpired(EXPIRY.minusNanos(1))).isFalse();
  }

  @Test
  void expiredExactlyAtExpiresAt() {
    assertThat(mapping(EXPIRY).isExpired(EXPIRY)).isTrue();
  }

  @Test
  void expiredAfterExpiresAt() {
    UrlMapping mapping = mapping(EXPIRY);

    assertThat(mapping.isExpired(EXPIRY.plusNanos(1))).isTrue();
    assertThat(mapping.isExpired(EXPIRY.plus(Duration.ofDays(30)))).isTrue();
  }

  @Test
  void isExpiredRejectsNullReferenceInstant() {
    assertThatThrownBy(() -> mapping(EXPIRY).isExpired(null))
        .isInstanceOf(NullPointerException.class);
  }

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

  @Test
  void equalityIsBasedOnShortCode() {
    UrlMapping a = mapping(null);
    UrlMapping sameCode =
        new UrlMapping("abc123", "https://other.example", CREATED, EXPIRY, CodeSource.DB_SEQUENCE);
    UrlMapping otherCode =
        new UrlMapping("xyz789", "https://example.com/some/path", CREATED, null, CodeSource.REDIS);

    assertThat(a).isEqualTo(sameCode).hasSameHashCodeAs(sameCode).isNotEqualTo(otherCode);
  }

  @Test
  void codeSourceWireValuesMatchTheMigrationCheckConstraint() {
    assertThat(CodeSource.REDIS.wireValue()).isEqualTo("redis");
    assertThat(CodeSource.DB_SEQUENCE.wireValue()).isEqualTo("db_sequence");
    assertThat(CodeSource.values()).containsExactly(CodeSource.REDIS, CodeSource.DB_SEQUENCE);
  }

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
