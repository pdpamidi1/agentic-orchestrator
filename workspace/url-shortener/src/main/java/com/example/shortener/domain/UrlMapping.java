/*
 * UrlMapping.java — JPA entity for one row of the urls table (short code -> long URL)
 *
 * Layer: domain. The system-of-record representation of a short link, mapped 1:1 onto the table
 * created by V1__create_urls_table.sql and validated against it at start-up (ddl-auto: validate).
 * Written by UrlWriteService, read by UrlReadService through UrlMappingRepository; the expiry rule
 * that decides between 302 and 410 lives here (isExpired). Contains the AttributeConverter that
 * persists CodeSource by its wire value. Serves AC-1, AC-7, AC-9 and ADR-002 (data model).
 */
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
 *
 * <p><b>Responsibility.</b> Hold the persisted state of a mapping and answer the one domain
 * question asked of it, {@link #isExpired(Instant)}. It has no setters: a mapping is written once
 * and never updated in this release (no edit, no delete, no hit counting).
 *
 * <p><b>Invariants.</b> {@code shortCode}, {@code longUrl}, {@code createdAt} and {@code
 * codeSource} are non-null for every instance built through the public constructor; the database
 * enforces the same with {@code NOT NULL} plus the format and length check constraints. {@code
 * expiresAt == null} means "never expires". {@code createdBy} is always {@code null}. The natural
 * key is {@code shortCode} (primary key, {@code varchar(32)}); there is no surrogate id.
 *
 * <p><b>Identity.</b> {@link #equals(Object)} compares {@code shortCode} only and {@link
 * #hashCode()} is constant, the standard pattern for JPA entities whose identity must be stable
 * across persistence-context boundaries and proxying; do not use instances as keys in large hash
 * maps.
 *
 * <p><b>Thread-safety and lifecycle.</b> Not thread-safe (mutable JPA-managed fields), but
 * effectively immutable after construction because nothing mutates it; instances are scoped to one
 * transaction ({@code open-in-view: false}). Not a Spring bean.
 *
 * <p><b>Design choice.</b> A plain entity with an explicit converter (rather than a plain
 * {@code @Enumerated} mapping) keeps the stored strings under the application's control and
 * identical to the API's {@code code_source} values, so the database check constraint, the JSON and
 * the Java enum cannot drift apart.
 */
@Entity
@Table(name = "urls")
public class UrlMapping {

  /**
   * Primary key: generated base62 code or custom alias, 3 to 32 characters from {@code [0-9a-zA-Z]}
   * ({@code urls_short_code_format_chk}). Case-sensitive: {@code "Abc"} and {@code "abc"} are
   * different links.
   */
  @Id
  @Column(name = "short_code", nullable = false, length = 32)
  private String shortCode;

  /** The redirect target, an absolute http/https URL of 1 to 2048 characters. */
  @Column(name = "long_url", nullable = false, length = 2048)
  private String longUrl;

  /** Creation instant in UTC ({@code timestamptz}); set from the write service's clock. */
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /**
   * Expiry instant in UTC, or {@code null} for a link that never expires. Indexed partially ({@code
   * idx_urls_expires_at WHERE expires_at IS NOT NULL}) for future sweeps; rows are not deleted on
   * expiry in this release, they answer 410.
   */
  @Column(name = "expires_at")
  private Instant expiresAt;

  /** Reserved for a future auth scenario; always {@code null} (non-goal: no user attribution). */
  @Column(name = "created_by", length = 255)
  private String createdBy;

  /**
   * Which counter produced {@code shortCode}, stored as {@code 'redis'} / {@code 'db_sequence'}
   * ({@code varchar(16)}, {@code urls_code_source_chk}) via {@link CodeSourceConverter}.
   */
  @Convert(converter = CodeSourceConverter.class)
  @Column(name = "code_source", nullable = false, length = 16)
  private CodeSource codeSource;

  /**
   * Required by JPA. Protected so that only the persistence provider (and subclasses/proxies) can
   * create an instance without the mandatory columns.
   */
  protected UrlMapping() {}

  /**
   * Creates a new mapping.
   *
   * <p>{@code createdBy} is fixed to {@code null}; there is intentionally no parameter for it.
   *
   * @param shortCode generated base62 code or custom alias (primary key)
   * @param longUrl absolute http/https target URL
   * @param createdAt creation instant (UTC)
   * @param expiresAt optional expiry instant; {@code null} means the link never expires
   * @param codeSource which counter produced the code
   * @throws NullPointerException when {@code shortCode}, {@code longUrl}, {@code createdAt} or
   *     {@code codeSource} is {@code null}
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

  /**
   * The short code (primary key).
   *
   * @return the generated code or custom alias, never {@code null} for a constructed instance
   */
  public String getShortCode() {
    return shortCode;
  }

  /**
   * The redirect target.
   *
   * @return the stored absolute http/https URL
   */
  public String getLongUrl() {
    return longUrl;
  }

  /**
   * When the mapping was created.
   *
   * @return the creation instant (UTC)
   */
  public Instant getCreatedAt() {
    return createdAt;
  }

  /**
   * Expiry instant, or {@code null} when the link never expires.
   *
   * @return the expiry instant (UTC) or {@code null}
   */
  public Instant getExpiresAt() {
    return expiresAt;
  }

  /**
   * Always {@code null} in this release (non-goal: no user attribution, no PII).
   *
   * @return {@code null}
   */
  public String getCreatedBy() {
    return createdBy;
  }

  /**
   * Which counter produced the short code.
   *
   * @return {@link CodeSource#REDIS} or {@link CodeSource#DB_SEQUENCE}
   */
  public CodeSource getCodeSource() {
    return codeSource;
  }

  /**
   * Whether this mapping has expired at the given instant.
   *
   * <p>The boundary is inclusive: at exactly {@code expiresAt} the link is already expired. The
   * reference instant is passed in rather than read from the system clock so the read service can
   * inject a test clock and so the same "now" is used for the cache TTL computation.
   *
   * @param now the reference instant
   * @return {@code true} only when {@code expires_at} is non-null and not after {@code now}, i.e.
   *     the link is expired exactly at and after its expiry instant
   * @throws NullPointerException when {@code now} is {@code null}
   */
  public boolean isExpired(Instant now) {
    Objects.requireNonNull(now, "now");
    return expiresAt != null && !expiresAt.isAfter(now);
  }

  /**
   * Entity equality by primary key: two mappings are equal when they are the same object or carry
   * the same non-null {@code shortCode}. An instance whose key is still {@code null} (JPA default
   * constructor, not yet populated) equals nothing but itself.
   *
   * @param other the object to compare with
   * @return {@code true} for the same instance or the same short code
   */
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

  /**
   * Constant hash code, consistent with {@link #equals(Object)} for all instances including those
   * whose key is not yet assigned. Deliberately trades hash distribution for stability across the
   * entity lifecycle (the usual JPA recommendation).
   *
   * @return the same value for every {@code UrlMapping}
   */
  @Override
  public int hashCode() {
    return UrlMapping.class.hashCode();
  }

  /**
   * Diagnostic rendering with the key, timestamps and origin. {@code longUrl} is intentionally
   * omitted so that log lines do not carry arbitrary client-supplied URLs.
   *
   * @return a single-line description
   */
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

  /**
   * Persists {@link CodeSource} as its wire value ({@code 'redis'} / {@code 'db_sequence'}).
   *
   * <p>Applied explicitly via {@code @Convert} on {@link UrlMapping#codeSource} (not {@code
   * autoApply}), so it cannot be picked up by accident elsewhere. {@code null} maps to {@code null}
   * in both directions, although the column is {@code NOT NULL}. Stateless and thread-safe;
   * instantiated by the JPA provider.
   *
   * <p><b>Why a converter and not {@code @Enumerated}.</b> {@code EnumType.STRING} would store the
   * Java constant name ({@code REDIS}), which differs from the contract's {@code redis} and from
   * the database check constraint; {@code EnumType.ORDINAL} would break on reordering. The
   * converter makes {@link CodeSource#wireValue()} the single source of truth.
   */
  @Converter
  public static class CodeSourceConverter implements AttributeConverter<CodeSource, String> {

    /**
     * Enum to column value.
     *
     * @param attribute the entity attribute, may be {@code null}
     * @return its wire value, or {@code null} when {@code attribute} is {@code null}
     */
    @Override
    public String convertToDatabaseColumn(CodeSource attribute) {
      return attribute == null ? null : attribute.wireValue();
    }

    /**
     * Column value to enum.
     *
     * @param dbData the stored string, may be {@code null}
     * @return the matching constant, or {@code null} when {@code dbData} is {@code null}
     * @throws IllegalArgumentException when the stored string is not a known wire value, which the
     *     check constraint should make impossible
     */
    @Override
    public CodeSource convertToEntityAttribute(String dbData) {
      return dbData == null ? null : CodeSource.fromWire(dbData);
    }
  }
}
