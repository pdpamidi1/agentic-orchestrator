/*
 * OutboxPublishServiceTest.java — claim bounds, state transitions, exhaustion and no duplicates
 *
 * Layer: test (unit). A Mockito ClickOutboxRepository whose claimBatch answers from an in-memory
 * table (PENDING, due, limited), a Mockito ClickEventProducer and a real RetryExecutor with a
 * recording sleeper. Pins: the claim asks for exactly analytics.outbox.batch-size rows; success
 * marks PUBLISHED with published_at = clock; a transient failure schedules a retry and succeeds;
 * exhaustion marks FAILED, counts the metric and logs without the digest; a second pass republishes
 * nothing; non-publish failures propagate; publishDueBatch is @Transactional (AC-3, AC-4, AC-5,
 * AC-10). No Spring context, no broker.
 */
package com.example.shortener.analytics.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.shortener.analytics.config.AnalyticsProperties;
import com.example.shortener.analytics.config.AnalyticsProperties.Jitter;
import com.example.shortener.analytics.domain.ClickOutboxEntry;
import com.example.shortener.analytics.domain.OutboxStatus;
import com.example.shortener.analytics.kafka.ClickEventMessage;
import com.example.shortener.analytics.kafka.ClickEventProducer;
import com.example.shortener.analytics.outbox.OutboxPublishService.PublishSummary;
import com.example.shortener.analytics.repository.ClickOutboxRepository;
import com.example.shortener.analytics.support.RetryExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.annotation.Transactional;

/** Unit tests of {@link OutboxPublishService}. */
class OutboxPublishServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-14T10:15:30Z");
  private static final int BATCH = 3;
  private static final int MAX_ATTEMPTS = 3;
  private static final String HASH = "c".repeat(64);

  private final ClickOutboxRepository repository = mock(ClickOutboxRepository.class);
  private final ClickEventProducer producer = mock(ClickEventProducer.class);
  private final OutboxMetrics metrics = new OutboxMetrics();
  private final List<Duration> sleeps = new ArrayList<>();
  private final RetryExecutor retryExecutor =
      new RetryExecutor(
          new AnalyticsProperties.Retry(MAX_ATTEMPTS, 100, 10_000, Jitter.NONE),
          Duration.ofMillis(50),
          new SplittableRandom(42L),
          sleeps::add);
  private final AnalyticsProperties properties =
      new AnalyticsProperties(
          "unit-test-salt-0123456789",
          new AnalyticsProperties.Outbox(500, BATCH),
          new AnalyticsProperties.Retry(MAX_ATTEMPTS, 100, 10_000, Jitter.NONE),
          new AnalyticsProperties.Kafka(50),
          new AnalyticsProperties.Retention(90));
  private final OutboxPublishService service =
      new OutboxPublishService(
          repository,
          producer,
          retryExecutor,
          metrics,
          properties,
          Clock.fixed(NOW, ZoneOffset.UTC));

  /** The in-memory click_outbox table behind the mocked repository. */
  private final List<ClickOutboxEntry> table = new ArrayList<>();

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(OutboxPublishService.class);

  @BeforeEach
  void stubRepositoryAndLogs() {
    when(repository.claimBatch(any(Instant.class), anyInt()))
        .thenAnswer(
            inv -> {
              Instant now = inv.getArgument(0);
              int limit = inv.getArgument(1);
              return table.stream()
                  .filter(e -> e.getStatus() == OutboxStatus.PENDING)
                  .filter(e -> !e.getNextAttemptAt().isAfter(now))
                  .limit(limit)
                  .toList();
            });
    when(repository.save(any(ClickOutboxEntry.class))).thenAnswer(inv -> inv.getArgument(0));
    logs.start();
    serviceLogger.addAppender(logs);
  }

  @AfterEach
  void detachLogs() {
    serviceLogger.detachAppender(logs);
  }

  private ClickOutboxEntry row(String key, String code, String referrer) {
    ClickOutboxEntry entry =
        new ClickOutboxEntry(key, code, NOW.minusSeconds(5), HASH, referrer, NOW.minusSeconds(5));
    table.add(entry);
    return entry;
  }

  private void producerSucceeds() {
    when(producer.send(any(ClickEventMessage.class)))
        .thenReturn(CompletableFuture.completedFuture(null));
  }

  private void producerFails() {
    when(producer.send(any(ClickEventMessage.class)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
  }

  /** The claim asks for exactly batch-size rows and only those are published. */
  @Test
  void claimsAtMostBatchSizeRows() {
    producerSucceeds();
    for (int i = 0; i < BATCH + 2; i++) {
      row("key-" + i, "code" + i, null);
    }

    PublishSummary summary = service.publishDueBatch();

    verify(repository).claimBatch(NOW, BATCH);
    assertThat(service.batchSize()).isEqualTo(BATCH);
    assertThat(summary).isEqualTo(new PublishSummary(BATCH, BATCH, 0));
    verify(producer, times(BATCH)).send(any(ClickEventMessage.class));
    assertThat(table.stream().filter(e -> e.getStatus() == OutboxStatus.PUBLISHED)).hasSize(BATCH);
    assertThat(table.stream().filter(e -> e.getStatus() == OutboxStatus.PENDING)).hasSize(2);
  }

  /** A successful publish marks PUBLISHED with published_at and the contract message content. */
  @Test
  void successfulPublishMarksRowPublished() {
    producerSucceeds();
    ClickOutboxEntry entry = row("key-1", "promo2024", "news.example");

    PublishSummary summary = service.publishDueBatch();

    assertThat(summary).isEqualTo(new PublishSummary(1, 1, 0));
    assertThat(entry.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    assertThat(entry.getPublishedAt()).isEqualTo(NOW);
    assertThat(entry.getAttempts()).isEqualTo(1);
    assertThat(metrics.publishedCount()).isEqualTo(1);
    assertThat(metrics.publishFailedCount()).isZero();
    verify(repository).save(entry);

    ArgumentCaptor<ClickEventMessage> captor = ArgumentCaptor.forClass(ClickEventMessage.class);
    verify(producer).send(captor.capture());
    ClickEventMessage message = captor.getValue();
    assertThat(message.idempotencyKey()).isEqualTo("key-1");
    assertThat(message.key()).isEqualTo("promo2024");
    assertThat(message.occurredAt()).isEqualTo(entry.getOccurredAt());
    assertThat(message.hashedIp()).isEqualTo(HASH);
    assertThat(message.referrerHost()).isEqualTo("news.example");
    assertThat(sleeps).isEmpty();
  }

  /** A transient failure is retried after the backoff and the row ends PUBLISHED. */
  @Test
  void transientFailureIsRetriedThenPublished() {
    AtomicInteger calls = new AtomicInteger();
    when(producer.send(any(ClickEventMessage.class)))
        .thenAnswer(
            inv ->
                calls.incrementAndGet() == 1
                    ? CompletableFuture.failedFuture(new IllegalStateException("hiccup"))
                    : CompletableFuture.completedFuture(null));
    ClickOutboxEntry entry = row("key-1", "promo2024", null);

    PublishSummary summary = service.publishDueBatch();

    assertThat(summary).isEqualTo(new PublishSummary(1, 1, 0));
    assertThat(entry.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    assertThat(entry.getAttempts()).isEqualTo(2);
    assertThat(entry.getNextAttemptAt()).isEqualTo(NOW.plusMillis(100));
    assertThat(sleeps).containsExactly(Duration.ofMillis(100));
    assertThat(metrics.publishFailedCount()).isZero();
  }

  /** Exhausting the budget parks the row as FAILED, counts it and logs without the digest. */
  @Test
  void exhaustedRetriesMarkRowFailedAndCountMetric() {
    producerFails();
    ClickOutboxEntry entry = row("key-1", "promo2024", "news.example");

    PublishSummary summary = service.publishDueBatch();

    assertThat(summary).isEqualTo(new PublishSummary(1, 0, 1));
    assertThat(entry.getStatus()).isEqualTo(OutboxStatus.FAILED);
    assertThat(entry.getPublishedAt()).isNull();
    assertThat(entry.getAttempts()).isEqualTo(MAX_ATTEMPTS);
    verify(producer, times(MAX_ATTEMPTS)).send(any(ClickEventMessage.class));
    assertThat(sleeps).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200));
    assertThat(metrics.publishFailedCount()).isEqualTo(1);
    assertThat(metrics.publishedCount()).isZero();
    verify(repository).save(entry);

    List<String> lines = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    assertThat(lines).anySatisfy(l -> assertThat(l).contains("FAILED").contains("key-1"));
    assertThat(lines).allSatisfy(l -> assertThat(l).doesNotContain(HASH));
    assertThat(lines).allSatisfy(l -> assertThat(l).doesNotContain("news.example"));
  }

  /** A FAILED row is never picked up again; a second pass republishes nothing. */
  @Test
  void failedRowIsNeverRetriedOnLaterPasses() {
    producerFails();
    row("key-1", "promo2024", null);
    service.publishDueBatch();

    PublishSummary second = service.publishDueBatch();

    assertThat(second).isEqualTo(PublishSummary.EMPTY);
    verify(producer, times(MAX_ATTEMPTS)).send(any(ClickEventMessage.class));
    assertThat(metrics.publishFailedCount()).isEqualTo(1);
  }

  /** Published rows are not claimed again: a second pass sends nothing. */
  @Test
  void secondPassDoesNotPublishDuplicates() {
    producerSucceeds();
    row("key-1", "promo2024", null);
    row("key-2", "promo2024", null);

    PublishSummary first = service.publishDueBatch();
    PublishSummary second = service.publishDueBatch();

    assertThat(first).isEqualTo(new PublishSummary(2, 2, 0));
    assertThat(second).isEqualTo(PublishSummary.EMPTY);
    verify(producer, times(2)).send(any(ClickEventMessage.class));
    verify(repository, times(2)).claimBatch(NOW, BATCH);
  }

  /** A mixed batch: one row succeeds, one is parked; each is saved with its own outcome. */
  @Test
  void mixedBatchRecordsEachOutcome() {
    ClickOutboxEntry good = row("good", "promo2024", null);
    ClickOutboxEntry bad = row("bad", "promo2024", null);
    when(producer.send(any(ClickEventMessage.class)))
        .thenAnswer(
            inv ->
                ((ClickEventMessage) inv.getArgument(0)).idempotencyKey().equals("good")
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(new IllegalStateException("no")));

    PublishSummary summary = service.publishDueBatch();

    assertThat(summary).isEqualTo(new PublishSummary(2, 1, 1));
    assertThat(good.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    assertThat(bad.getStatus()).isEqualTo(OutboxStatus.FAILED);
    verify(repository).save(good);
    verify(repository).save(bad);
  }

  /** A database failure propagates so the transaction rolls back and rows stay PENDING. */
  @Test
  void repositoryFailurePropagates() {
    when(repository.claimBatch(any(Instant.class), anyInt()))
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));

    assertThatThrownBy(service::publishDueBatch)
        .isInstanceOf(DataAccessResourceFailureException.class);
  }

  /** Claim, publish and state changes share one read-write transaction. */
  @Test
  void publishDueBatchIsTransactional() throws NoSuchMethodException {
    Transactional annotation =
        OutboxPublishService.class.getMethod("publishDueBatch").getAnnotation(Transactional.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.readOnly()).isFalse();
  }

  /** Every collaborator is mandatory. */
  @Test
  void nullCollaboratorsAreRejected() {
    assertThatThrownBy(
            () -> new OutboxPublishService(null, producer, retryExecutor, metrics, properties))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new OutboxPublishService(repository, null, retryExecutor, metrics, properties))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new OutboxPublishService(repository, producer, null, metrics, properties))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new OutboxPublishService(repository, producer, retryExecutor, null, properties))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new OutboxPublishService(repository, producer, retryExecutor, metrics, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new OutboxPublishService(
                    repository, producer, retryExecutor, metrics, properties, null))
        .isInstanceOf(NullPointerException.class);
  }
}
