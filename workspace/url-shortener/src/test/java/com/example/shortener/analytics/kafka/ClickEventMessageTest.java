/*
 * ClickEventMessageTest.java — contract shape of the url.clicked value object
 *
 * Layer: test (unit). Pins the key (short_code), the value fields in contract order
 * {short_code, occurred_at, hashed_ip, referrer_host}, that the idempotency key is not part of
 * the value, null handling and that toString never leaks the digest (AC-3, AC-10).
 */
package com.example.shortener.analytics.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests of {@link ClickEventMessage}. */
class ClickEventMessageTest {

  private static final String KEY = "0b0f6f8e-6c4e-4b7b-9a3b-1a2b3c4d5e6f";
  private static final String HASH = "a".repeat(64);
  private static final Instant AT = Instant.parse("2026-09-14T10:15:30Z");

  /** The key is the short code and the payload lists the four contract fields in order. */
  @Test
  void payloadCarriesContractFieldsInOrder() {
    ClickEventMessage message = new ClickEventMessage(KEY, "promo2024", AT, HASH, "news.example");

    assertThat(message.key()).isEqualTo("promo2024");
    Map<String, Object> payload = message.payload();
    assertThat(payload.keySet())
        .containsExactly("short_code", "occurred_at", "hashed_ip", "referrer_host");
    assertThat(payload)
        .containsEntry("short_code", "promo2024")
        .containsEntry("occurred_at", "2026-09-14T10:15:30Z")
        .containsEntry("hashed_ip", HASH)
        .containsEntry("referrer_host", "news.example");
    assertThat(payload).doesNotContainValue(KEY);
    assertThatThrownBy(() -> payload.put("x", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /** A missing referrer is an explicit null field, not an absent key. */
  @Test
  void nullReferrerHostIsKeptAsNullField() {
    ClickEventMessage message = new ClickEventMessage(KEY, "promo2024", AT, HASH, null);

    assertThat(message.payload()).containsKey("referrer_host");
    assertThat(message.payload().get("referrer_host")).isNull();
  }

  /** Mandatory fields are validated at construction. */
  @Test
  void mandatoryFieldsAreRequired() {
    assertThatThrownBy(() -> new ClickEventMessage(null, "c", AT, HASH, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ClickEventMessage(KEY, null, AT, HASH, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ClickEventMessage(KEY, "c", null, HASH, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ClickEventMessage(KEY, "c", AT, null, null))
        .isInstanceOf(NullPointerException.class);
  }

  /** Diagnostics never include the digest or the referrer. */
  @Test
  void toStringOmitsDigestAndReferrer() {
    ClickEventMessage message = new ClickEventMessage(KEY, "promo2024", AT, HASH, "news.example");

    assertThat(message.toString())
        .contains(KEY)
        .contains("promo2024")
        .doesNotContain(HASH)
        .doesNotContain("news.example");
  }
}
