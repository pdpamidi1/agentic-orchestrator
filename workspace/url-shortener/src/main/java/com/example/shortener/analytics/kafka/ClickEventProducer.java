/*
 * ClickEventProducer.java — publishes url.clicked messages through Spring Kafka
 *
 * Layer: analytics.kafka, the only production package (besides consumer and config) allowed to
 * import org.apache.kafka / org.springframework.kafka (ArchitectureTest rule). Wraps the
 * auto-configured KafkaTemplate: topic url.clicked, key = short_code, value = JSON
 * {short_code, occurred_at, hashed_ip, referrer_host}, header idempotency-key (AC-3, AC-4, AC-10).
 * Timeouts and retries are the caller's concern (analytics.support.RetryExecutor).
 */
package com.example.shortener.analytics.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes one {@link ClickEventMessage} to the {@code url.clicked} topic.
 *
 * <p><b>Responsibility.</b> Build the {@link ProducerRecord} exactly as the contract prescribes and
 * hand it to the {@link KafkaTemplate}; nothing else. The returned future completes when the broker
 * acknowledges the record and fails when the send fails; a serialisation failure yields an
 * already-failed future rather than an exception, so callers treat every failure uniformly.
 *
 * <p><b>Thread-safety and lifecycle.</b> Immutable singleton bean; {@link KafkaTemplate} and {@link
 * ObjectMapper} are thread-safe.
 */
@Component
public class ClickEventProducer {

  /** Topic every click event is published to. */
  public static final String TOPIC = "url.clicked";

  /** Header carrying the producer-side idempotency key. */
  public static final String HEADER_IDEMPOTENCY_KEY = "idempotency-key";

  /** Spring Kafka template (auto-configured from {@code spring.kafka.*}). */
  private final KafkaTemplate<String, String> kafkaTemplate;

  /** Serialises the value payload. */
  private final ObjectMapper objectMapper;

  /**
   * Creates the producer.
   *
   * @param kafkaTemplate the template to send with
   * @param objectMapper the JSON mapper for the value
   * @throws NullPointerException when an argument is {@code null}
   */
  public ClickEventProducer(
      KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
    this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  /**
   * Builds the record for a message: topic {@value #TOPIC}, key = short code, JSON value from
   * {@link ClickEventMessage#payload()}, header {@value #HEADER_IDEMPOTENCY_KEY} in UTF-8.
   *
   * @param message the message
   * @return the record to send
   * @throws NullPointerException when {@code message} is {@code null}
   * @throws RuntimeException when the payload cannot be serialised
   */
  public ProducerRecord<String, String> toRecord(ClickEventMessage message) {
    Objects.requireNonNull(message, "message");
    String value = objectMapper.writeValueAsString(message.payload());
    ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, message.key(), value);
    record
        .headers()
        .add(HEADER_IDEMPOTENCY_KEY, message.idempotencyKey().getBytes(StandardCharsets.UTF_8));
    return record;
  }

  /**
   * Sends one message.
   *
   * @param message the message
   * @return a future that completes when the broker acknowledged the record, or fails
   * @throws NullPointerException when {@code message} is {@code null}
   */
  public CompletableFuture<Void> send(ClickEventMessage message) {
    Objects.requireNonNull(message, "message");
    ProducerRecord<String, String> record;
    try {
      record = toRecord(message);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
    try {
      return kafkaTemplate.send(record).thenApply(result -> null);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }
}
