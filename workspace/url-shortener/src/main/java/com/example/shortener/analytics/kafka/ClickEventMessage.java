/*
 * ClickEventMessage.java — the url.clicked message published for one click
 *
 * Layer: analytics.kafka. Value object built by the outbox relay from a click_outbox row and handed
 * to ClickEventProducer. Carries exactly the contract fields of the url.clicked topic: key =
 * short_code, value = {short_code, occurred_at, hashed_ip, referrer_host}, header idempotency-key
 * (AC-3, AC-10). Never holds a raw client address: hashed_ip is the salted SHA-256 digest.
 */
package com.example.shortener.analytics.kafka;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One {@code url.clicked} message.
 *
 * <p><b>Invariants.</b> {@code idempotencyKey}, {@code shortCode}, {@code occurredAt} and {@code
 * hashedIp} are non-null; {@code referrerHost} may be {@code null} (absent or unparsable Referer).
 * The record is immutable and thread-safe.
 *
 * @param idempotencyKey producer-side dedupe key; sent as the {@code idempotency-key} header, not
 *     in the value
 * @param shortCode the redirected short code; also the message key
 * @param occurredAt when the redirect happened (UTC)
 * @param hashedIp salted SHA-256 hex digest of the client address
 * @param referrerHost host of the Referer header, or {@code null}
 */
public record ClickEventMessage(
    String idempotencyKey,
    String shortCode,
    Instant occurredAt,
    String hashedIp,
    String referrerHost) {

  /** Value field: the redirected short code. */
  public static final String FIELD_SHORT_CODE = "short_code";

  /** Value field: ISO-8601 UTC instant of the click. */
  public static final String FIELD_OCCURRED_AT = "occurred_at";

  /** Value field: salted SHA-256 hex digest of the client address. */
  public static final String FIELD_HASHED_IP = "hashed_ip";

  /** Value field: Referer host or {@code null}. */
  public static final String FIELD_REFERRER_HOST = "referrer_host";

  /**
   * Validates the mandatory fields.
   *
   * @throws NullPointerException when a mandatory component is {@code null}
   */
  public ClickEventMessage {
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(shortCode, "shortCode");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(hashedIp, "hashedIp");
  }

  /** The Kafka message key: the short code. */
  public String key() {
    return shortCode;
  }

  /**
   * The message value as an ordered map of contract fields, ready for JSON serialisation. {@code
   * occurred_at} is rendered as an ISO-8601 string; {@code referrer_host} is present with a {@code
   * null} value when unknown.
   *
   * @return an unmodifiable map in contract order
   */
  public Map<String, Object> payload() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put(FIELD_SHORT_CODE, shortCode);
    fields.put(FIELD_OCCURRED_AT, occurredAt.toString());
    fields.put(FIELD_HASHED_IP, hashedIp);
    fields.put(FIELD_REFERRER_HOST, referrerHost);
    return Collections.unmodifiableMap(fields);
  }

  /** Diagnostic rendering without the digest or the referrer. */
  @Override
  public String toString() {
    return "ClickEventMessage{idempotencyKey='"
        + idempotencyKey
        + "', shortCode='"
        + shortCode
        + "', occurredAt="
        + occurredAt
        + '}';
  }
}
