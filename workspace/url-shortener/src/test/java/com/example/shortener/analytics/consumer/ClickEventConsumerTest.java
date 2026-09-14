/*
 * ClickEventConsumerTest.java — deserialisation, delegation, retry policy and terminal states
 *
 * Layer: test (unit). A Mockito ClickStatsAggregator, a real RetryExecutor with a recording sleeper
 * and a direct executor, real ConsumerMetrics and ObjectMapper. Pins: a contract record is parsed
 * into the ClickEventMessage (hashed_ip flows through untouched, referrer_host may be null) and
 * applied once; a duplicate is reported and counted without error; a transient failure is retried
 * with backoff and succeeds; a permanent failure ends in EXHAUSTED after max-attempts with the
 * error logged and counted and no exception thrown (no infinite redelivery); malformed records are
 * rejected without any attempt; the listener annotation targets url.clicked; no log line carries
 * the hashed address (AC-5, AC-6, AC-7, AC-10). No Spring context, no broker.
 */
package com.example.shortener.analytics.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.shortener.analytics.aggregation.ClickStatsAggregator;
import com.example.shortener.analytics.config.AnalyticsProperties;
import com.example.shortener.analytics.config.AnalyticsProperties.Jitter;
import com.example.shortener.analytics.consumer.ClickEventConsumer.Outcome;
import com.example.shortener.analytics.kafka.ClickEventMessage;
import com.example.shortener.analytics.kafka.ClickEventProducer;
import com.example.shortener.analytics.support.RetryExecutor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.ObjectMapper;

/** Unit tests of {@link ClickEventConsumer}. */
class ClickEventConsumerTest {

  private static final int MAX_ATTEMPTS = 3;
  private static final String HASH = "d".repeat(64);
  private static final String KEY = "evt-42";
  private static final Instant OCCURRED = Instant.parse("2026-09-14T10:15:30Z");

  private final ClickStatsAggregator aggregator = mock(ClickStatsAggregator.class);
  private final ConsumerMetrics metrics = new ConsumerMetrics();
  private final List<Duration> sleeps = new ArrayList<>();
  private final RetryExecutor retryExecutor =
      new RetryExecutor(
          new AnalyticsProperties.Retry(MAX_ATTEMPTS, 100, 10_000, Jitter.NONE),
          Duration.ofMillis(500),
          new SplittableRandom(42L),
          sleeps::add);
  private final ClickEventConsumer consumer =
      new ClickEventConsumer(aggregator, retryExecutor, metrics, new ObjectMapper(), Runnable::run);

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private Logger logger;

  @BeforeEach
  void captureLogs() {
    logger = (Logger) LoggerFactory.getLogger(ClickEventConsumer.class);
    logger.setLevel(Level.DEBUG);
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    logger.detachAppender(logs);
    logs.stop();
  }

  /** Given a contract record, when consumed, then the parsed event is applied exactly once. */
  @Test
  void singleEventIsDeserialisedAndApplied() {
    when(aggregator.apply(any())).thenReturn(ClickStatsAggregator.Outcome.APPLIED);

    Outcome outcome = consumer.consume(record(KEY, value("abc123", "news.example.org")));

    assertThat(outcome).isEqualTo(Outcome.APPLIED);
    ArgumentCaptor<ClickEventMessage> captor = ArgumentCaptor.forClass(ClickEventMessage.class);
    verify(aggregator, times(1)).apply(captor.capture());
    ClickEventMessage event = captor.getValue();
    assertThat(event.idempotencyKey()).isEqualTo(KEY);
    assertThat(event.shortCode()).isEqualTo("abc123");
    assertThat(event.occurredAt()).isEqualTo(OCCURRED);
    assertThat(event.hashedIp()).isEqualTo(HASH);
    assertThat(event.referrerHost()).isEqualTo("news.example.org");
    assertThat(metrics.appliedCount()).isEqualTo(1);
    assertThat(sleeps).isEmpty();
  }

  /** Given a record without referrer, when parsed, then referrer_host is null. */
  @Test
  void nullReferrerIsAccepted() {
    ClickEventMessage event = consumer.deserialize(record(KEY, value("abc123", null)));

    assertThat(event.referrerHost()).isNull();
    assertThat(event.hashedIp()).isEqualTo(HASH);
  }

  /** Given a redelivered key, when consumed, then it is a counted no-op, not a failure (AC-6). */
  @Test
  void duplicateEventIsReportedAndCounted() {
    when(aggregator.apply(any())).thenReturn(ClickStatsAggregator.Outcome.DUPLICATE);

    Outcome outcome = consumer.consume(record(KEY, value("abc123", null)));

    assertThat(outcome).isEqualTo(Outcome.DUPLICATE);
    assertThat(metrics.duplicateCount()).isEqualTo(1);
    assertThat(metrics.appliedCount()).isZero();
    assertThat(metrics.exhaustedCount()).isZero();
    verify(aggregator, times(1)).apply(any());
  }

  /** Given one transient failure, when consumed, then it is retried after backoff and applied. */
  @Test
  void transientFailureIsRetriedWithBackoff() {
    when(aggregator.apply(any()))
        .thenThrow(new DataAccessResourceFailureException("db hiccup"))
        .thenReturn(ClickStatsAggregator.Outcome.APPLIED);

    Outcome outcome = consumer.consume(record(KEY, value("abc123", null)));

    assertThat(outcome).isEqualTo(Outcome.APPLIED);
    verify(aggregator, times(2)).apply(any());
    assertThat(sleeps).containsExactly(Duration.ofMillis(100));
    assertThat(logs.list)
        .anySatisfy(
            line -> {
              assertThat(line.getLevel()).isEqualTo(Level.WARN);
              assertThat(line.getFormattedMessage()).contains("attempt 1").contains(KEY);
            });
  }

  /**
   * Given a permanent failure, when consumed, then attempts are bounded and the record ends
   * EXHAUSTED: logged, counted, acknowledged (no exception), no infinite redelivery (AC-5).
   */
  @Test
  void retryExhaustionEndsInLoggedAndMeteredTerminalState() {
    when(aggregator.apply(any())).thenThrow(new DataAccessResourceFailureException("db down"));

    Outcome outcome = consumer.consume(record(KEY, value("abc123", null)));

    assertThat(outcome).isEqualTo(Outcome.EXHAUSTED);
    verify(aggregator, times(MAX_ATTEMPTS)).apply(any());
    assertThat(sleeps).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200));
    assertThat(metrics.exhaustedCount()).isEqualTo(1);
    assertThat(metrics.appliedCount()).isZero();
    assertThat(logs.list)
        .anySatisfy(
            line -> {
              assertThat(line.getLevel()).isEqualTo(Level.ERROR);
              assertThat(line.getFormattedMessage())
                  .contains("Giving up")
                  .contains(KEY)
                  .contains("abc123")
                  .contains(MAX_ATTEMPTS + " attempt(s)")
                  .contains("db down");
            });
  }

  /** Given a slow aggregation, when consumed, then the per-attempt timeout counts as a failure. */
  @Test
  void slowAttemptTimesOutAndIsRetried() {
    RetryExecutor tight =
        new RetryExecutor(
            new AnalyticsProperties.Retry(2, 1, 1, Jitter.NONE),
            Duration.ofMillis(20),
            new SplittableRandom(1L),
            sleeps::add);
    ClickEventConsumer slowConsumer =
        new ClickEventConsumer(
            aggregator,
            tight,
            metrics,
            new ObjectMapper(),
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    when(aggregator.apply(any()))
        .thenAnswer(
            invocation -> {
              Thread.sleep(300);
              return ClickStatsAggregator.Outcome.APPLIED;
            });

    Outcome outcome = slowConsumer.consume(record(KEY, value("abc123", null)));

    assertThat(outcome).isEqualTo(Outcome.EXHAUSTED);
    assertThat(metrics.exhaustedCount()).isEqualTo(1);
    assertThat(logs.list)
        .anySatisfy(line -> assertThat(line.getFormattedMessage()).contains("TimeoutException"));
  }

  /** Given malformed records, when consumed, then they are rejected without any attempt. */
  @Test
  void malformedRecordsAreRejectedWithoutRetry() {
    ConsumerRecord<String, String> noHeader =
        new ConsumerRecord<>(ClickEventProducer.TOPIC, 0, 1L, "abc123", value("abc123", null));
    ConsumerRecord<String, String> notJson = record(KEY, "{not json");
    ConsumerRecord<String, String> missingField =
        record(KEY, "{\"short_code\":\"abc123\",\"hashed_ip\":\"" + HASH + "\"}");
    ConsumerRecord<String, String> badInstant =
        record(
            KEY,
            "{\"short_code\":\"abc123\",\"occurred_at\":\"yesterday\",\"hashed_ip\":\""
                + HASH
                + "\",\"referrer_host\":null}");
    ConsumerRecord<String, String> nullValue = record(KEY, null);

    for (ConsumerRecord<String, String> bad :
        List.of(noHeader, notJson, missingField, badInstant, nullValue)) {
      assertThat(consumer.consume(bad)).isEqualTo(Outcome.REJECTED);
    }

    verify(aggregator, never()).apply(any());
    assertThat(metrics.rejectedCount()).isEqualTo(5);
    assertThat(sleeps).isEmpty();
    assertThat(logs.list)
        .filteredOn(line -> line.getLevel() == Level.ERROR)
        .hasSize(5)
        .allSatisfy(line -> assertThat(line.getFormattedMessage()).contains("Rejected"));
  }

  /** Given every outcome, when logged, then the hashed address never appears (AC-10). */
  @Test
  void logsNeverCarryHashedIp() {
    when(aggregator.apply(any()))
        .thenReturn(ClickStatsAggregator.Outcome.APPLIED)
        .thenReturn(ClickStatsAggregator.Outcome.DUPLICATE)
        .thenThrow(new DataAccessResourceFailureException("db down"));

    consumer.consume(record(KEY, value("abc123", "news.example.org")));
    consumer.consume(record(KEY, value("abc123", "news.example.org")));
    consumer.consume(record(KEY, value("abc123", "news.example.org")));
    consumer.consume(record(KEY, "{\"hashed_ip\":\"" + HASH + "\"}"));

    assertThat(logs.list).isNotEmpty();
    for (ILoggingEvent line : logs.list) {
      assertThat(line.getFormattedMessage())
          .doesNotContain(HASH)
          .doesNotContain("news.example.org")
          .doesNotContain("hashed_ip\":");
    }
  }

  /** The listener must subscribe to url.clicked in its own group and be startup-toggleable. */
  @Test
  void listenerAnnotationTargetsUrlClickedTopic() throws NoSuchMethodException {
    KafkaListener annotation =
        ClickEventConsumer.class
            .getMethod("onMessage", ConsumerRecord.class)
            .getAnnotation(KafkaListener.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.topics()).containsExactly(ClickEventProducer.TOPIC);
    assertThat(annotation.groupId()).isEqualTo(ClickEventConsumer.GROUP_ID);
    assertThat(annotation.id()).isEqualTo(ClickEventConsumer.LISTENER_ID);
    assertThat(annotation.autoStartup()).contains("analytics.consumer.auto-startup");
  }

  /** onMessage delegates to consume. */
  @Test
  void onMessageDelegatesToConsume() {
    when(aggregator.apply(any())).thenReturn(ClickStatsAggregator.Outcome.APPLIED);

    consumer.onMessage(record(KEY, value("abc123", null)));

    verify(aggregator, times(1)).apply(any());
    assertThat(metrics.appliedCount()).isEqualTo(1);
  }

  /** Constructor arguments are mandatory. */
  @Test
  void rejectsNullCollaborators() {
    ObjectMapper mapper = new ObjectMapper();
    assertThatThrownBy(() -> new ClickEventConsumer(null, retryExecutor, metrics, mapper))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ClickEventConsumer(aggregator, null, metrics, mapper))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ClickEventConsumer(aggregator, retryExecutor, null, mapper))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ClickEventConsumer(aggregator, retryExecutor, metrics, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> consumer.consume(null)).isInstanceOf(NullPointerException.class);
  }

  private static ConsumerRecord<String, String> record(String idempotencyKey, String value) {
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>(ClickEventProducer.TOPIC, 0, 7L, "abc123", value);
    record
        .headers()
        .add(
            ClickEventProducer.HEADER_IDEMPOTENCY_KEY,
            idempotencyKey.getBytes(StandardCharsets.UTF_8));
    return record;
  }

  private static String value(String shortCode, String referrerHost) {
    return "{\"short_code\":\""
        + shortCode
        + "\",\"occurred_at\":\""
        + OCCURRED
        + "\",\"hashed_ip\":\""
        + HASH
        + "\",\"referrer_host\":"
        + (referrerHost == null ? "null" : "\"" + referrerHost + "\"")
        + "}";
  }
}
