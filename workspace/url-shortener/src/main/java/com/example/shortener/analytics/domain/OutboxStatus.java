/*
 * OutboxStatus.java — Lifecycle state of one click_outbox row
 *
 * Layer: analytics.domain (leaf; depends on nothing inside the application). Persisted by its
 * constant name through ClickOutboxEntry.OutboxStatusConverter into the varchar(16) status column,
 * whose CHECK constraint (V2__click_analytics.sql) admits exactly these three values.
 */
package com.example.shortener.analytics.domain;

/**
 * State of a click event in the transactional outbox.
 *
 * <p>Transitions: {@link #PENDING} to {@link #PUBLISHED} on a successful Kafka acknowledgement,
 * {@link #PENDING} to {@link #PENDING} (with a later {@code next_attempt_at}) on a failed attempt
 * that still has retries left, {@link #PENDING} to {@link #FAILED} when the attempt budget is
 * exhausted. {@code PUBLISHED} and {@code FAILED} are terminal.
 */
public enum OutboxStatus {
  /** Recorded on the redirect path and not yet acknowledged by Kafka; eligible for the relay. */
  PENDING,
  /** Acknowledged by Kafka; kept until the retention job purges it. */
  PUBLISHED,
  /** Retry budget exhausted; kept for inspection, never picked up by the relay again. */
  FAILED
}
