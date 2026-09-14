/*
 * ClickEventProducerTest.java — topic, key, header and JSON value of a published click
 *
 * Layer: test (unit). With a Mockito KafkaTemplate captures the ProducerRecord and asserts topic
 * url.clicked, key = short_code, header idempotency-key = the key bytes and a JSON value holding
 * exactly {short_code, occurred_at, hashed_ip, referrer_host}; failures surface as failed futures
 * (AC-3, AC-4, AC-10). No broker, no Spring context.
 */
package com.example.shortener.analytics.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

/** Unit tests of {@link ClickEventProducer}. */
class ClickEventProducerTest {

  private static final String KEY = "0b0f6f8e-6c4e-4b7b-9a3b-1a2b3c4d5e6f";
  private static final String HASH = "b".repeat(64);
  private static final Instant AT = Instant.parse("2026-09-14T10:15:30Z");

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);

  private final JsonMapper mapper = new JsonMapper();
  private final ClickEventProducer producer = new ClickEventProducer(template, mapper);

  /** The record goes to url.clicked, keyed by short code, with the idempotency-key header. */
  @Test
  @SuppressWarnings("unchecked")
  void sendsToUrlClickedKeyedByShortCodeWithIdempotencyHeader() throws Exception {
    when(template.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    ClickEventMessage message = new ClickEventMessage(KEY, "promo2024", AT, HASH, "news.example");

    CompletableFuture<Void> future = producer.send(message);

    assertThat(future.get()).isNull();
    ArgumentCaptor<ProducerRecord<String, String>> captor =
        ArgumentCaptor.forClass(ProducerRecord.class);
    verify(template).send(captor.capture());
    ProducerRecord<String, String> record = captor.getValue();
    assertThat(record.topic()).isEqualTo("url.clicked");
    assertThat(record.key()).isEqualTo("promo2024");
    Header header = record.headers().lastHeader("idempotency-key");
    assertThat(header).isNotNull();
    assertThat(new String(header.value(), StandardCharsets.UTF_8)).isEqualTo(KEY);
    assertThat(record.headers().toArray()).hasSize(1);

    Map<String, Object> value = mapper.readValue(record.value(), Map.class);
    assertThat(value)
        .hasSize(4)
        .containsEntry("short_code", "promo2024")
        .containsEntry("occurred_at", "2026-09-14T10:15:30Z")
        .containsEntry("hashed_ip", HASH)
        .containsEntry("referrer_host", "news.example");
    assertThat(record.value()).doesNotContain(KEY);
  }

  /** A missing referrer is serialised as JSON null. */
  @Test
  @SuppressWarnings("unchecked")
  void nullReferrerHostIsSerialisedAsNull() {
    ProducerRecord<String, String> record =
        producer.toRecord(new ClickEventMessage(KEY, "promo2024", AT, HASH, null));

    Map<String, Object> value = mapper.readValue(record.value(), Map.class);
    assertThat(value).containsKey("referrer_host");
    assertThat(value.get("referrer_host")).isNull();
  }

  /** A failed send surfaces as a failed future, never as a thrown exception. */
  @Test
  @SuppressWarnings("unchecked")
  void brokerFailureCompletesFutureExceptionally() {
    when(template.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker down")));

    CompletableFuture<Void> future =
        producer.send(new ClickEventMessage(KEY, "promo2024", AT, HASH, null));

    assertThat(future).isCompletedExceptionally();
    assertThatThrownBy(future::get)
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(KafkaException.class);
  }

  /** A template that throws synchronously is also reported as a failed future. */
  @Test
  @SuppressWarnings("unchecked")
  void synchronousTemplateFailureCompletesFutureExceptionally() {
    when(template.send(any(ProducerRecord.class))).thenThrow(new IllegalStateException("closed"));

    CompletableFuture<Void> future =
        producer.send(new ClickEventMessage(KEY, "promo2024", AT, HASH, null));

    assertThat(future).isCompletedExceptionally();
  }

  /** Collaborators and the message are mandatory. */
  @Test
  void nullArgumentsAreRejected() {
    assertThatThrownBy(() -> new ClickEventProducer(null, mapper))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ClickEventProducer(template, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> producer.send(null)).isInstanceOf(NullPointerException.class);
  }
}
