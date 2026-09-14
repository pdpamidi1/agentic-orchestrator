/*
 * ProcessedClickEvent.java — JPA entity for one row of processed_click_event (consumer dedupe)
 *
 * Layer: analytics.domain (leaf). Written by the Kafka consumer in the same transaction as the
 * aggregate update so that a redelivered event (same idempotency key) is applied at most once.
 * Mapped 1:1 onto processed_click_event from V2__click_analytics.sql (AC-6, AC-12).
 */
package com.example.shortener.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * Marker that a click event with a given idempotency key has already been applied to the aggregates
 * ({@code processed_click_event}).
 *
 * <p><b>Invariants.</b> {@code idempotencyKey} (primary key) and {@code processedAt} are non-null.
 * Rows are write-once. Identity by {@code idempotencyKey}; constant {@link #hashCode()}. Not
 * thread-safe; not a Spring bean.
 */
@Entity
@Table(name = "processed_click_event")
public class ProcessedClickEvent {

  /**
   * Primary key: the event's idempotency key, identical to {@code click_outbox.idempotency_key}.
   */
  @Id
  @Column(name = "idempotency_key", nullable = false, length = 128)
  private String idempotencyKey;

  /** When the consumer applied the event (UTC). */
  @Column(name = "processed_at", nullable = false)
  private Instant processedAt;

  /** Required by JPA. */
  protected ProcessedClickEvent() {}

  /**
   * Creates a marker.
   *
   * @param idempotencyKey the event's idempotency key
   * @param processedAt when the event was applied (UTC)
   * @throws NullPointerException when either argument is {@code null}
   */
  public ProcessedClickEvent(String idempotencyKey, Instant processedAt) {
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    this.processedAt = Objects.requireNonNull(processedAt, "processedAt");
  }

  /** The idempotency key (primary key). */
  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  /** When the event was applied (UTC). */
  public Instant getProcessedAt() {
    return processedAt;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ProcessedClickEvent that)) {
      return false;
    }
    return idempotencyKey != null && idempotencyKey.equals(that.idempotencyKey);
  }

  @Override
  public int hashCode() {
    return ProcessedClickEvent.class.hashCode();
  }

  @Override
  public String toString() {
    return "ProcessedClickEvent{idempotencyKey='"
        + idempotencyKey
        + "', processedAt="
        + processedAt
        + '}';
  }
}
