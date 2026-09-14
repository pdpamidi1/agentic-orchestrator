/*
 * ClickOutboxEntry.java — JPA entity for one row of the click_outbox table
 *
 * Layer: analytics.domain (leaf). One click recorded on the redirect path, waiting to be relayed to
 * Kafka by the outbox relay. Mapped 1:1 onto click_outbox from V2__click_analytics.sql and checked
 * against it at start-up (ddl-auto: validate). Carries only a salted SHA-256 hex digest of the
 * client address (hashed_ip), never the address itself (AC-1, AC-6, AC-7, AC-10, AC-12).
 */
package com.example.shortener.analytics.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One click event in the transactional outbox ({@code click_outbox}).
 *
 * <p><b>Responsibility.</b> Hold the event payload plus its relay bookkeeping ({@code status},
 * {@code attempts}, {@code nextAttemptAt}, {@code publishedAt}) and apply the three state
 * transitions the relay needs: {@link #markPublished}, {@link #scheduleRetry} and {@link
 * #markFailed}.
 *
 * <p><b>Invariants.</b> {@code idempotencyKey}, {@code shortCode}, {@code occurredAt}, {@code
 * hashedIp}, {@code status}, {@code nextAttemptAt} and {@code createdAt} are non-null; {@code
 * hashedIp} is exactly 64 lowercase hex characters (a SHA-256 digest), enforced here and by {@code
 * click_outbox_hashed_ip_chk}; {@code attempts >= 0}; {@code publishedAt} is non-null exactly when
 * {@code status == PUBLISHED}. {@code idempotencyKey} is unique ({@code
 * uq_click_outbox_idempotency_key}); the surrogate {@code id} is database-generated.
 *
 * <p><b>Identity.</b> {@link #equals(Object)} compares the business key {@code idempotencyKey},
 * which is stable before and after the identity column is assigned; {@link #hashCode()} is constant
 * (standard JPA entity pattern).
 *
 * <p><b>Thread-safety.</b> Not thread-safe; instances are confined to one transaction. Not a Spring
 * bean.
 */
@Entity
@Table(name = "click_outbox")
public class ClickOutboxEntry {

  /** Shape of a SHA-256 digest rendered as lowercase hex. */
  private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

  /** Surrogate key, assigned by the database identity column. */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  /** Producer-side idempotency key; unique per click, reused by the consumer for dedupe. */
  @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
  private String idempotencyKey;

  /** The short code that was redirected. */
  @Column(name = "short_code", nullable = false, length = 32)
  private String shortCode;

  /** When the redirect happened (UTC). */
  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  /** Salted SHA-256 hex digest of the client address; the only trace of the client that is kept. */
  @Column(name = "hashed_ip", nullable = false, length = 64)
  private String hashedIp;

  /** Host part of the Referer header, or {@code null} when absent or unparsable. */
  @Column(name = "referrer_host", length = 255)
  private String referrerHost;

  /** Relay state, stored as {@code 'PENDING'} / {@code 'PUBLISHED'} / {@code 'FAILED'}. */
  @Convert(converter = OutboxStatusConverter.class)
  @Column(name = "status", nullable = false, length = 16)
  private OutboxStatus status;

  /** Number of publish attempts made so far. */
  @Column(name = "attempts", nullable = false)
  private int attempts;

  /** Earliest instant at which the relay may (re)try this row (UTC). */
  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  /** When Kafka acknowledged the event (UTC), or {@code null} while not yet published. */
  @Column(name = "published_at")
  private Instant publishedAt;

  /** When the row was written (UTC). */
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /** Required by JPA. */
  protected ClickOutboxEntry() {}

  /**
   * Creates a new pending entry that is immediately eligible for the relay.
   *
   * @param idempotencyKey unique key of this click (1 to 128 characters)
   * @param shortCode the redirected short code
   * @param occurredAt when the redirect happened (UTC)
   * @param hashedIp salted SHA-256 digest of the client address as 64 lowercase hex characters
   * @param referrerHost host of the Referer header, or {@code null}
   * @param createdAt when the row is being written (UTC); also the first {@code nextAttemptAt}
   * @throws NullPointerException when a mandatory argument is {@code null}
   * @throws IllegalArgumentException when {@code hashedIp} is not a 64-character lowercase hex
   *     string or {@code idempotencyKey} is blank or too long
   */
  public ClickOutboxEntry(
      String idempotencyKey,
      String shortCode,
      Instant occurredAt,
      String hashedIp,
      String referrerHost,
      Instant createdAt) {
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    if (idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
      throw new IllegalArgumentException("idempotencyKey must be 1 to 128 characters");
    }
    this.shortCode = Objects.requireNonNull(shortCode, "shortCode");
    this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    this.hashedIp = Objects.requireNonNull(hashedIp, "hashedIp");
    if (!SHA256_HEX.matcher(hashedIp).matches()) {
      throw new IllegalArgumentException("hashedIp must be a 64-character lowercase hex digest");
    }
    this.referrerHost = referrerHost;
    this.status = OutboxStatus.PENDING;
    this.attempts = 0;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.nextAttemptAt = createdAt;
    this.publishedAt = null;
  }

  /**
   * Records a successful publish: status becomes {@link OutboxStatus#PUBLISHED}, the attempt
   * counter is incremented and {@code publishedAt} is set.
   *
   * @param at acknowledgement instant (UTC)
   * @throws IllegalStateException when the entry is not {@link OutboxStatus#PENDING}
   */
  public void markPublished(Instant at) {
    requirePending();
    this.attempts++;
    this.status = OutboxStatus.PUBLISHED;
    this.publishedAt = Objects.requireNonNull(at, "at");
  }

  /**
   * Records a failed attempt with retries left: the entry stays {@link OutboxStatus#PENDING}, the
   * attempt counter is incremented and the relay will not pick it up before {@code nextAttemptAt}.
   *
   * @param nextAttemptAt earliest instant of the next attempt (UTC)
   * @throws IllegalStateException when the entry is not {@link OutboxStatus#PENDING}
   */
  public void scheduleRetry(Instant nextAttemptAt) {
    requirePending();
    this.attempts++;
    this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
  }

  /**
   * Records a failed attempt that exhausted the retry budget: status becomes {@link
   * OutboxStatus#FAILED} (terminal) and the attempt counter is incremented.
   *
   * @throws IllegalStateException when the entry is not {@link OutboxStatus#PENDING}
   */
  public void markFailed() {
    requirePending();
    this.attempts++;
    this.status = OutboxStatus.FAILED;
  }

  private void requirePending() {
    if (status != OutboxStatus.PENDING) {
      throw new IllegalStateException("entry is already " + status);
    }
  }

  /** Database-generated surrogate key, or {@code null} before the first flush. */
  public Long getId() {
    return id;
  }

  /** Unique key of this click. */
  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  /** The redirected short code. */
  public String getShortCode() {
    return shortCode;
  }

  /** When the redirect happened (UTC). */
  public Instant getOccurredAt() {
    return occurredAt;
  }

  /** Salted SHA-256 hex digest of the client address. */
  public String getHashedIp() {
    return hashedIp;
  }

  /** Referer host, or {@code null}. */
  public String getReferrerHost() {
    return referrerHost;
  }

  /** Current relay state. */
  public OutboxStatus getStatus() {
    return status;
  }

  /** Publish attempts made so far. */
  public int getAttempts() {
    return attempts;
  }

  /** Earliest instant of the next relay attempt (UTC). */
  public Instant getNextAttemptAt() {
    return nextAttemptAt;
  }

  /** Kafka acknowledgement instant (UTC), or {@code null}. */
  public Instant getPublishedAt() {
    return publishedAt;
  }

  /** When the row was written (UTC). */
  public Instant getCreatedAt() {
    return createdAt;
  }

  /**
   * Entity equality by business key: same instance or same non-null {@code idempotencyKey}.
   *
   * @param other the object to compare with
   * @return {@code true} for the same instance or the same idempotency key
   */
  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ClickOutboxEntry that)) {
      return false;
    }
    return idempotencyKey != null && idempotencyKey.equals(that.idempotencyKey);
  }

  /** Constant hash code, consistent with {@link #equals(Object)} across the entity lifecycle. */
  @Override
  public int hashCode() {
    return ClickOutboxEntry.class.hashCode();
  }

  /** Diagnostic rendering without the hashed address or the referrer. */
  @Override
  public String toString() {
    return "ClickOutboxEntry{id="
        + id
        + ", idempotencyKey='"
        + idempotencyKey
        + "', shortCode='"
        + shortCode
        + "', occurredAt="
        + occurredAt
        + ", status="
        + status
        + ", attempts="
        + attempts
        + ", nextAttemptAt="
        + nextAttemptAt
        + '}';
  }

  /**
   * Persists {@link OutboxStatus} by its constant name ({@code 'PENDING'} etc.), matching {@code
   * click_outbox_status_chk}. Applied explicitly via {@code @Convert}; {@code null} maps to {@code
   * null} in both directions. Stateless and thread-safe.
   */
  @Converter
  public static class OutboxStatusConverter implements AttributeConverter<OutboxStatus, String> {

    @Override
    public String convertToDatabaseColumn(OutboxStatus attribute) {
      return attribute == null ? null : attribute.name();
    }

    @Override
    public OutboxStatus convertToEntityAttribute(String dbData) {
      return dbData == null ? null : OutboxStatus.valueOf(dbData);
    }
  }
}
