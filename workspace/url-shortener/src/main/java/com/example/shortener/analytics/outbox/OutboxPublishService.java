/*
 * OutboxPublishService.java — relays one claimed batch of click_outbox rows to Kafka
 *
 * Layer: analytics.outbox (depends on analytics.domain, .repository, .kafka, .support, .config).
 * One transactional pass: claim <= analytics.outbox.batch-size PENDING rows with
 * SELECT ... FOR UPDATE SKIP LOCKED, publish each through ClickEventProducer under the
 * RetryExecutor policy, mark PUBLISHED (published_at) on acknowledgement or FAILED (terminal,
 * logged without the digest, counted) on exhaustion (AC-3, AC-4, AC-5, AC-10). The row locks are
 * held for the whole pass so a concurrent relay instance skips these rows and never publishes them
 * twice.
 */
package com.example.shortener.analytics.outbox;

import com.example.shortener.analytics.config.AnalyticsProperties;
import com.example.shortener.analytics.domain.ClickOutboxEntry;
import com.example.shortener.analytics.kafka.ClickEventMessage;
import com.example.shortener.analytics.kafka.ClickEventProducer;
import com.example.shortener.analytics.repository.ClickOutboxRepository;
import com.example.shortener.analytics.support.RetryExecutor;
import com.example.shortener.analytics.support.RetryExecutor.RetryExhaustedException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes one batch of due {@code click_outbox} rows.
 *
 * <p><b>Responsibility.</b> {@link #publishDueBatch} is the unit of work of the relay: claim, then
 * for every row build the {@link ClickEventMessage}, run {@link ClickEventProducer#send} through
 * the {@link RetryExecutor}, and record the outcome on the entity. Each intermediate failure is
 * recorded with {@link ClickOutboxEntry#scheduleRetry} (attempt counter, next_attempt_at) so the
 * persisted {@code attempts} equals the number of sends actually made.
 *
 * <p><b>Transaction.</b> The whole pass runs in one read-write transaction: the claim locks the
 * rows, every state change is flushed at commit. A failure that is not a publish failure (database
 * error, interrupt) propagates and rolls the pass back, leaving the rows {@code PENDING} for the
 * next tick; nothing is lost and nothing is parked by mistake.
 *
 * <p><b>Privacy.</b> Log lines carry the short code, the idempotency key and the attempt count,
 * never the hashed address nor the referrer (AC-10).
 *
 * <p><b>Thread-safety and lifecycle.</b> Immutable singleton bean; must only be called from the
 * analytics scheduler thread (see {@link OutboxPoller}), never from a request thread.
 */
@Service
public class OutboxPublishService {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublishService.class);

  /**
   * Outcome of one pass.
   *
   * @param claimed rows claimed by the pass
   * @param published rows acknowledged by Kafka and marked {@code PUBLISHED}
   * @param failed rows parked as {@code FAILED}
   */
  public record PublishSummary(int claimed, int published, int failed) {

    /** A pass that found nothing to do. */
    public static final PublishSummary EMPTY = new PublishSummary(0, 0, 0);
  }

  private final ClickOutboxRepository repository;
  private final ClickEventProducer producer;
  private final RetryExecutor retryExecutor;
  private final OutboxMetrics metrics;
  private final int batchSize;
  private final Clock clock;

  /**
   * Creates the service with the system UTC clock (the constructor Spring uses).
   *
   * @param repository the {@code click_outbox} repository
   * @param producer the Kafka producer
   * @param retryExecutor the retry policy executor
   * @param metrics relay counters
   * @param properties analytics settings (batch size)
   * @throws NullPointerException when any argument is {@code null}
   */
  @Autowired
  public OutboxPublishService(
      ClickOutboxRepository repository,
      ClickEventProducer producer,
      RetryExecutor retryExecutor,
      OutboxMetrics metrics,
      AnalyticsProperties properties) {
    this(repository, producer, retryExecutor, metrics, properties, Clock.systemUTC());
  }

  /**
   * Creates the service with an explicit clock.
   *
   * @param repository the {@code click_outbox} repository
   * @param producer the Kafka producer
   * @param retryExecutor the retry policy executor
   * @param metrics relay counters
   * @param properties analytics settings (batch size)
   * @param clock source of {@code published_at} and {@code next_attempt_at}
   * @throws NullPointerException when any argument is {@code null}
   */
  public OutboxPublishService(
      ClickOutboxRepository repository,
      ClickEventProducer producer,
      RetryExecutor retryExecutor,
      OutboxMetrics metrics,
      AnalyticsProperties properties,
      Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.producer = Objects.requireNonNull(producer, "producer");
    this.retryExecutor = Objects.requireNonNull(retryExecutor, "retryExecutor");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.batchSize = Objects.requireNonNull(properties, "properties").outbox().batchSize();
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Maximum rows claimed per pass ({@code analytics.outbox.batch-size}). */
  public int batchSize() {
    return batchSize;
  }

  /**
   * Claims and publishes one batch of due rows.
   *
   * @return what happened to the claimed rows
   * @throws RuntimeException any non-publish failure, after the transaction was rolled back
   */
  @Transactional
  public PublishSummary publishDueBatch() {
    List<ClickOutboxEntry> batch = repository.claimBatch(clock.instant(), batchSize);
    if (batch.isEmpty()) {
      return PublishSummary.EMPTY;
    }
    int published = 0;
    int failed = 0;
    for (ClickOutboxEntry entry : batch) {
      if (publish(entry)) {
        published++;
      } else {
        failed++;
      }
      repository.save(entry);
    }
    log.debug("Outbox pass: claimed={} published={} failed={}", batch.size(), published, failed);
    return new PublishSummary(batch.size(), published, failed);
  }

  /** Publishes one row; returns {@code true} when it ended {@code PUBLISHED}. */
  private boolean publish(ClickOutboxEntry entry) {
    ClickEventMessage message = toMessage(entry);
    try {
      retryExecutor.execute(
          () -> producer.send(message),
          (attempt, backoff, cause) -> {
            entry.scheduleRetry(clock.instant().plus(backoff));
            log.warn(
                "Publish attempt {} failed for short code '{}' key {}: {}; retrying in {} ms",
                attempt,
                entry.getShortCode(),
                entry.getIdempotencyKey(),
                describe(cause),
                backoff.toMillis());
          });
      entry.markPublished(clock.instant());
      metrics.incrementPublished();
      return true;
    } catch (RetryExhaustedException e) {
      entry.markFailed();
      metrics.incrementPublishFailed();
      log.error(
          "Giving up on short code '{}' key {} after {} attempt(s); row marked FAILED: {}",
          entry.getShortCode(),
          entry.getIdempotencyKey(),
          e.getAttempts(),
          describe(e.getCause()));
      return false;
    }
  }

  /** Maps a row onto the contract message. */
  static ClickEventMessage toMessage(ClickOutboxEntry entry) {
    return new ClickEventMessage(
        entry.getIdempotencyKey(),
        entry.getShortCode(),
        entry.getOccurredAt(),
        entry.getHashedIp(),
        entry.getReferrerHost());
  }

  /** Exception class and message only; a cause never carries the address. */
  private static String describe(Throwable cause) {
    if (cause == null) {
      return "unknown cause";
    }
    return cause.getClass().getSimpleName()
        + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
  }
}
