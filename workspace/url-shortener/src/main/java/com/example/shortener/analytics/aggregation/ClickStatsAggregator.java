/*
 * ClickStatsAggregator.java — applies one url.clicked event to click_stats / click_stats_daily
 * exactly once
 *
 * Layer: analytics.aggregation (depends on analytics.repository and the analytics.kafka message
 * value object only; imports no org.apache.kafka / org.springframework.kafka type). Called by
 * analytics.consumer.ClickEventConsumer for every delivered event. One transaction per event:
 * claim the idempotency key in processed_click_event, then upsert the lifetime total (moving
 * last_clicked_at forward only) and the UTC day bucket derived from occurred_at (AC-5, AC-6, AC-7,
 * AC-10).
 */
package com.example.shortener.analytics.aggregation;

import com.example.shortener.analytics.kafka.ClickEventMessage;
import com.example.shortener.analytics.repository.ClickStatsRepository;
import com.example.shortener.analytics.repository.ProcessedClickEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent aggregation of one click event into the per-link aggregates.
 *
 * <p><b>Responsibility.</b> {@link #apply} is the unit of work of the consumer. Inside a single
 * read-write transaction it (1) inserts the event's idempotency key into {@code
 * processed_click_event} with {@code ON CONFLICT DO NOTHING}; when the insert affects no row the
 * event was already applied and the method returns {@link Outcome#DUPLICATE} without touching any
 * aggregate; otherwise it (2) upserts {@code click_stats} ({@code total_clicks + 1}, {@code
 * last_clicked_at = GREATEST(existing, occurred_at)}, {@code updated_at = now}) and (3) upserts the
 * {@code click_stats_daily} row of the UTC calendar day of {@code occurred_at}.
 *
 * <p><b>Atomicity.</b> The dedupe marker and both aggregate rows commit or roll back together, so a
 * failure after the marker was written never leaves an event marked as processed but uncounted; the
 * consumer's next attempt starts from a clean slate. Because the upserts are single statements, two
 * consumers applying different events for the same short code serialise on the row without lost
 * updates, and an out-of-order (older) event never moves {@code last_clicked_at} backwards.
 *
 * <p><b>Day bucketing.</b> The bucket is {@code occurred_at} converted to a UTC date ({@link
 * #utcDay}); a click at {@code 23:59:59Z} and one at {@code 00:00:00Z} the next day land in two
 * different rows regardless of the JVM's default time zone.
 *
 * <p><b>Privacy.</b> Only the idempotency key, the short code and the instant are logged; the
 * hashed address and the referrer host carried by the message are neither logged nor stored here
 * (AC-10).
 *
 * <p><b>Thread-safety and lifecycle.</b> Immutable singleton bean; safe for concurrent use. The
 * {@link Transactional} boundary requires calls to go through the Spring proxy.
 */
@Service
public class ClickStatsAggregator {

  private static final Logger log = LoggerFactory.getLogger(ClickStatsAggregator.class);

  /** What {@link #apply} did with the event. */
  public enum Outcome {
    /** First delivery of this idempotency key: the aggregates were incremented. */
    APPLIED,
    /** The key was already processed: nothing changed. */
    DUPLICATE
  }

  private final ProcessedClickEventRepository processedEvents;
  private final ClickStatsRepository stats;
  private final Clock clock;

  /**
   * Creates the aggregator with the system UTC clock (the constructor Spring uses).
   *
   * @param processedEvents the dedupe repository
   * @param stats the aggregate repository
   * @throws NullPointerException when an argument is {@code null}
   */
  @Autowired
  public ClickStatsAggregator(
      ProcessedClickEventRepository processedEvents, ClickStatsRepository stats) {
    this(processedEvents, stats, Clock.systemUTC());
  }

  /**
   * Creates the aggregator with an explicit clock.
   *
   * @param processedEvents the dedupe repository
   * @param stats the aggregate repository
   * @param clock source of {@code processed_at} and {@code updated_at}
   * @throws NullPointerException when an argument is {@code null}
   */
  public ClickStatsAggregator(
      ProcessedClickEventRepository processedEvents, ClickStatsRepository stats, Clock clock) {
    this.processedEvents = Objects.requireNonNull(processedEvents, "processedEvents");
    this.stats = Objects.requireNonNull(stats, "stats");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Applies one event exactly once.
   *
   * @param event the deserialised {@code url.clicked} message
   * @return {@link Outcome#APPLIED} on the first delivery, {@link Outcome#DUPLICATE} afterwards
   * @throws NullPointerException when {@code event} is {@code null}
   * @throws RuntimeException any data-access failure, after the transaction was rolled back; the
   *     caller retries under its policy
   */
  @Transactional
  public Outcome apply(ClickEventMessage event) {
    Objects.requireNonNull(event, "event");
    Instant now = clock.instant();
    int claimed = processedEvents.insertIfAbsent(event.idempotencyKey(), now);
    if (claimed == 0) {
      log.info(
          "Duplicate click event ignored: key {} short code '{}'",
          event.idempotencyKey(),
          event.shortCode());
      return Outcome.DUPLICATE;
    }
    stats.incrementTotal(event.shortCode(), event.occurredAt(), now);
    stats.incrementDaily(event.shortCode(), utcDay(event.occurredAt()));
    log.debug(
        "Click event applied: key {} short code '{}' occurred at {}",
        event.idempotencyKey(),
        event.shortCode(),
        event.occurredAt());
    return Outcome.APPLIED;
  }

  /**
   * UTC calendar day of an instant: the {@code click_stats_daily} bucket.
   *
   * @param occurredAt when the click happened
   * @return the date of {@code occurredAt} in UTC
   * @throws NullPointerException when {@code occurredAt} is {@code null}
   */
  public static LocalDate utcDay(Instant occurredAt) {
    return Objects.requireNonNull(occurredAt, "occurredAt").atZone(ZoneOffset.UTC).toLocalDate();
  }
}
