package com.example.shortener.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapped 1:1 to the {@code urls} table created by {@code V1__create_urls_table.sql}.
 *
 * <p>{@code created_by} is reserved for a future auth scenario and is always left {@code null}
 * here; it is deliberately not settable through the public constructor.
 */
@Entity
@Table(name = "urls")
public class UrlMapping {

  @Id
  @Column(name = "short_code", nullable = false, length = 32)
  private String shortCode;

  @Column(name = "long_url", nullable = false, length = 2048)
  private String longUrl;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "created_by", length = 255)
  private String createdBy;

  @Convert(converter = CodeSourceConverter.class)
  @Column(name = "code_source", nullable = false, length = 16)
  private CodeSource codeSource;

  /** Required by JPA. */
  protected UrlMapping() {}

  /**
   * Creates a new mapping.
   *
   * @param shortCode generated base62 code or custom alias (primary key)
   * @param longUrl absolute http/https target URL
   * @param createdAt creation instant (UTC)
   * @param expiresAt optional expiry instant; {@code null} means the link never expires
   * @param codeSource which counter produced the code
   */
  public UrlMapping(
      String shortCode,
      String longUrl,
      Instant createdAt,
      Instant expiresAt,
      CodeSource codeSource) {
    this.shortCode = Objects.requireNonNull(shortCode, "shortCode");
    this.longUrl = Objects.requireNonNull(longUrl, "longUrl");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.expiresAt = expiresAt;
    this.createdBy = null;
    this.codeSource = Objects.requireNonNull(codeSource, "codeSource");
  }

  public String getShortCode() {
    return shortCode;
  }

  public String getLongUrl() {
    return longUrl;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  /** Expiry instant, or {@code null} when the link never expires. */
  public Instant getExpiresAt() {
    return expiresAt;
  }

  /** Always {@code null} in this release (non-goal: no user attribution, no PII). */
  public String getCreatedBy() {
    return createdBy;
  }

  public CodeSource getCodeSource() {
    return codeSource;
  }

  /**
   * Whether this mapping has expired at the given instant.
   *
   * @param now the reference instant
   * @return {@code true} only when {@code expires_at} is non-null and not after {@code now}, i.e.
   *     the link is expired exactly at and after its expiry instant
   */
  public boolean isExpired(Instant now) {
    Objects.requireNonNull(now, "now");
    return expiresAt != null && !expiresAt.isAfter(now);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof UrlMapping that)) {
      return false;
    }
    return shortCode != null && shortCode.equals(that.shortCode);
  }

  @Override
  public int hashCode() {
    return UrlMapping.class.hashCode();
  }

  @Override
  public String toString() {
    return "UrlMapping{shortCode='"
        + shortCode
        + "', createdAt="
        + createdAt
        + ", expiresAt="
        + expiresAt
        + ", codeSource="
        + codeSource
        + '}';
  }

  /** Persists {@link CodeSource} as its wire value ({@code 'redis'} / {@code 'db_sequence'}). */
  @Converter
  public static class CodeSourceConverter implements AttributeConverter<CodeSource, String> {

    @Override
    public String convertToDatabaseColumn(CodeSource attribute) {
      return attribute == null ? null : attribute.wireValue();
    }

    @Override
    public CodeSource convertToEntityAttribute(String dbData) {
      return dbData == null ? null : CodeSource.fromWire(dbData);
    }
  }
}
