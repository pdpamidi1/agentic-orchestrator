/*
 * ClickEventConsumer.java — Kafka listener on url.clicked feeding ClickStatsAggregator
 *
 * Layer: analytics.consumer, one of the three production packages (with analytics.kafka and
 * analytics.config) allowed to import org.apache.kafka / org.springframework.kafka
 * (ArchitectureTest rule). Deserialises each record of the url.clicked topic (key = short_code,
 * JSON value {short_code, occurred_at, hashed_ip, referrer_host}, header idempotency-key) into a
 * ClickEventMessage and delegates to analytics.aggregation.ClickStatsAggregator, whose single
 * transaction dedupes on processed_click_event and upserts the aggregates. Failures of the
 * aggregation run under the shared analytics.support.RetryExecutor policy (AMB-17: bounded
 * attempts, exponential backoff with jitter, per-attempt timeout); when the budget is exhausted the
 * record is logged, counted and acknowledged rather than redelivered forever (AC-5, AC-6, AC-7,
 * AC-10).
 */
package com.example.shortener.analytics.consumer;

import com.example.shortener.analytics.aggregation.ClickStatsAggregator;
import com.example.shortener.analytics.kafka.ClickEventMessage;
import com.example.shortener.analytics.kafka.ClickEventProducer;
import com.example.shortener.analytics.support.RetryExecutor;
import com.example.shortener.analytics.support.RetryExecutor.RetryExhaustedException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code url.clicked} records and aggregates them into {@code click_stats}.
 *
 * <p><b>Responsibility.</b> {@link #onMessage} is the Spring Kafka entry point; {@link #consume} is
 * the testable body and returns what happened to the record:
 *
 * <ul>
 *   <li>{@link Outcome#REJECTED}: the record is not a valid event (missing {@code idempotency-key}
 *       header, unparsable JSON, missing or malformed contract field). A poison record never
 *       becomes valid, so it is logged, counted and skipped without any retry.
 *   <li>{@link Outcome#APPLIED} / {@link Outcome#DUPLICATE}: the aggregator accepted the event
 *       (first delivery or replay of an already processed key). Duplicates are a no-op for the
 *       totals, {@code last_clicked_at} and the daily buckets (AC-6).
 *   <li>{@link Outcome#EXHAUSTED}: every attempt of the aggregation failed (database outage,
 *       timeout). The record is logged with class and message of the last cause, counted in {@link
 *       ConsumerMetrics#exhaustedCount()} and acknowledged so the partition keeps moving; because
 *       nothing was committed the event can be replayed later from the outbox / topic by an
 *       operator (AC-5).
 * </ul>
 *
 * <p><b>Retry.</b> Each attempt runs {@link ClickStatsAggregator#apply} on the {@link Executor} (a
 * virtual thread per attempt in production) so the {@link RetryExecutor} can bound it with {@code
 * analytics.kafka.timeout-ms}; the executor sleeps {@code min(cap, base * 2^n)} with jitter between
 * attempts and gives up after {@code analytics.retry.max-attempts}. The listener thread blocks for
 * the whole schedule, which is intended: the partition is not advanced until the record reached a
 * terminal state, and the schedule is bounded.
 *
 * <p><b>Privacy.</b> Log lines carry the topic coordinates, the idempotency key and the short code,
 * never the {@code hashed_ip} value nor the referrer host; no raw client address ever reaches this
 * class (AC-10).
 *
 * <p><b>Thread-safety and lifecycle.</b> Immutable singleton bean; the listener container calls it
 * from one consumer thread per partition assignment. The listener starts with the context unless
 * {@code analytics.consumer.auto-startup=false}.
 */
@Component
public class ClickEventConsumer {

  private static final Logger log = LoggerFactory.getLogger(ClickEventConsumer.class);

  /** Consumer group of the aggregation listener; one group so each event is counted once. */
  public static final String GROUP_ID = "click-stats-aggregator";

  /** Listener container id (for {@code KafkaListenerEndpointRegistry} lookups). */
  public static final String LISTENER_ID = "clickEventConsumer";

  private static final TypeReference<Map<String, Object>> VALUE_TYPE = new TypeReference<>() {};

  /** Terminal state of one delivered record. */
  public enum Outcome {
    /** First delivery: the aggregates were incremented. */
    APPLIED,
    /** Replay of a processed idempotency key: nothing changed. */
    DUPLICATE,
    /** Malformed record: skipped without retry. */
    REJECTED,
    /** Aggregation failed on every attempt: dropped after logging and counting. */
    EXHAUSTED
  }

  private final ClickStatsAggregator aggregator;
  private final RetryExecutor retryExecutor;
  private final ConsumerMetrics metrics;
  private final ObjectMapper objectMapper;
  private final Executor executor;

  /**
   * Creates the consumer Spring uses: attempts run on a virtual thread each.
   *
   * @param aggregator applies events to the aggregates
   * @param retryExecutor the shared retry policy
   * @param metrics consumer counters
   * @param objectMapper JSON mapper for the record value
   * @throws NullPointerException when an argument is {@code null}
   */
  @Autowired
  public ClickEventConsumer(
      ClickStatsAggregator aggregator,
      RetryExecutor retryExecutor,
      ConsumerMetrics metrics,
      ObjectMapper objectMapper) {
    this(
        aggregator,
        retryExecutor,
        metrics,
        objectMapper,
        Executors.newVirtualThreadPerTaskExecutor());
  }

  /**
   * Creates the consumer with an explicit attempt executor (tests use a direct executor).
   *
   * @param aggregator applies events to the aggregates
   * @param retryExecutor the shared retry policy
   * @param metrics consumer counters
   * @param objectMapper JSON mapper for the record value
   * @param executor runs each aggregation attempt
   * @throws NullPointerException when an argument is {@code null}
   */
  public ClickEventConsumer(
      ClickStatsAggregator aggregator,
      RetryExecutor retryExecutor,
      ConsumerMetrics metrics,
      ObjectMapper objectMapper,
      Executor executor) {
    this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
    this.retryExecutor = Objects.requireNonNull(retryExecutor, "retryExecutor");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  /**
   * Spring Kafka entry point for one {@code url.clicked} record.
   *
   * @param record the delivered record
   */
  @KafkaListener(
      id = LISTENER_ID,
      topics = ClickEventProducer.TOPIC,
      groupId = GROUP_ID,
      autoStartup = "${analytics.consumer.auto-startup:true}")
  public void onMessage(ConsumerRecord<String, String> record) {
    consume(record);
  }

  /**
   * Processes one record to a terminal state. Never throws for a record-level problem; only an
   * interrupt of the listener thread propagates (as {@link IllegalStateException}).
   *
   * @param record the delivered record
   * @return what happened to it
   * @throws NullPointerException when {@code record} is {@code null}
   */
  public Outcome consume(ConsumerRecord<String, String> record) {
    Objects.requireNonNull(record, "record");
    ClickEventMessage event;
    try {
      event = deserialize(record);
    } catch (RuntimeException e) {
      metrics.incrementRejected();
      log.error(
          "Rejected malformed click event at {}-{}@{} with key '{}': {}",
          record.topic(),
          record.partition(),
          record.offset(),
          record.key(),
          describe(e));
      return Outcome.REJECTED;
    }
    try {
      ClickStatsAggregator.Outcome applied =
          retryExecutor.execute(
              () -> CompletableFuture.supplyAsync(() -> aggregator.apply(event), executor),
              (attempt, backoff, cause) ->
                  log.warn(
                      "Aggregation attempt {} failed for key {} short code '{}': {}; retrying in"
                          + " {} ms",
                      attempt,
                      event.idempotencyKey(),
                      event.shortCode(),
                      describe(cause),
                      backoff.toMillis()));
      if (applied == ClickStatsAggregator.Outcome.DUPLICATE) {
        metrics.incrementDuplicate();
        return Outcome.DUPLICATE;
      }
      metrics.incrementApplied();
      return Outcome.APPLIED;
    } catch (RetryExhaustedException e) {
      metrics.incrementExhausted();
      log.error(
          "Giving up on click event key {} short code '{}' at {}-{}@{} after {} attempt(s);"
              + " record acknowledged without aggregation: {}",
          event.idempotencyKey(),
          event.shortCode(),
          record.topic(),
          record.partition(),
          record.offset(),
          e.getAttempts(),
          describe(e.getCause()));
      return Outcome.EXHAUSTED;
    }
  }

  /**
   * Parses a record into the contract message.
   *
   * @param record the delivered record
   * @return the event
   * @throws IllegalArgumentException when the header or a mandatory field is missing or malformed
   * @throws RuntimeException when the value is not a JSON object
   */
  ClickEventMessage deserialize(ConsumerRecord<String, String> record) {
    Header header = record.headers().lastHeader(ClickEventProducer.HEADER_IDEMPOTENCY_KEY);
    if (header == null || header.value() == null || header.value().length == 0) {
      throw new IllegalArgumentException(
          "missing " + ClickEventProducer.HEADER_IDEMPOTENCY_KEY + " header");
    }
    String idempotencyKey = new String(header.value(), StandardCharsets.UTF_8);
    if (record.value() == null) {
      throw new IllegalArgumentException("null value");
    }
    Map<String, Object> fields;
    try {
      fields = objectMapper.readValue(record.value(), VALUE_TYPE);
    } catch (RuntimeException e) {
      // Class name only: a parser message may quote the payload.
      throw new IllegalArgumentException(
          "value is not a JSON object (" + e.getClass().getSimpleName() + ")");
    }
    if (fields == null) {
      throw new IllegalArgumentException("value is not a JSON object");
    }
    String shortCode = requiredString(fields, ClickEventMessage.FIELD_SHORT_CODE);
    String occurredAtText = requiredString(fields, ClickEventMessage.FIELD_OCCURRED_AT);
    String hashedIp = requiredString(fields, ClickEventMessage.FIELD_HASHED_IP);
    Object referrer = fields.get(ClickEventMessage.FIELD_REFERRER_HOST);
    if (referrer != null && !(referrer instanceof String)) {
      throw new IllegalArgumentException(
          "field " + ClickEventMessage.FIELD_REFERRER_HOST + " must be a string or null");
    }
    Instant occurredAt;
    try {
      occurredAt = Instant.parse(occurredAtText);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(
          "field " + ClickEventMessage.FIELD_OCCURRED_AT + " is not an ISO-8601 instant", e);
    }
    return new ClickEventMessage(
        idempotencyKey, shortCode, occurredAt, hashedIp, (String) referrer);
  }

  private static String requiredString(Map<String, Object> fields, String name) {
    Object value = fields.get(name);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("field " + name + " is missing or not a non-empty string");
    }
    return text;
  }

  /** Exception class and message only; never a payload. */
  private static String describe(Throwable cause) {
    if (cause == null) {
      return "unknown cause";
    }
    return cause.getClass().getSimpleName()
        + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
  }
}
