/*
 * ClickStatsAggregatorTest.java — dedupe-first ordering, upsert arguments, UTC day bucket
 *
 * Layer: test (unit). Mockito repositories and a fixed clock. Pins: a first delivery claims the
 * idempotency key and then upserts the total (occurred_at, now) and the UTC daily bucket; a replay
 * of the same key returns DUPLICATE and never touches an aggregate; the day bucket follows UTC
 * across midnight regardless of the source offset; a repository failure propagates so the
 * transaction rolls back; apply is @Transactional; logs never carry the hashed address
 * (AC-5, AC-6, AC-7, AC-10). No Spring context, no database (see ClickStatsAggregatorIT).
 */
package com.example.shortener.analytics.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.shortener.analytics.aggregation.ClickStatsAggregator.Outcome;
import com.example.shortener.analytics.kafka.ClickEventMessage;
import com.example.shortener.analytics.repository.ClickStatsRepository;
import com.example.shortener.analytics.repository.ProcessedClickEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.annotation.Transactional;

/** Unit tests of {@link ClickStatsAggregator}. */
class ClickStatsAggregatorTest {

  private static final Instant NOW = Instant.parse("2026-09-14T10:15:30Z");
  private static final Instant OCCURRED = Instant.parse("2026-09-14T09:59:59Z");
  private static final String HASH = "a".repeat(64);
  private static final String KEY = "evt-0001";

  private final ProcessedClickEventRepository processed = mock(ProcessedClickEventRepository.class);
  private final ClickStatsRepository stats = mock(ClickStatsRepository.class);
  private final ClickStatsAggregator aggregator =
      new ClickStatsAggregator(processed, stats, Clock.fixed(NOW, ZoneOffset.UTC));

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private Logger logger;

  @BeforeEach
  void captureLogs() {
    logger = (Logger) LoggerFactory.getLogger(ClickStatsAggregator.class);
    logger.setLevel(Level.DEBUG);
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    logger.detachAppender(logs);
    logs.stop();
  }

  /** Given a fresh key, when applied, then the marker is claimed first and both upserts follow. */
  @Test
  void firstDeliveryClaimsKeyThenUpsertsTotalAndDailyBucket() {
    when(processed.insertIfAbsent(KEY, NOW)).thenReturn(1);

    Outcome outcome = aggregator.apply(event(KEY, "abc123", OCCURRED));

    assertThat(outcome).isEqualTo(Outcome.APPLIED);
    InOrder order = inOrder(processed, stats);
    order.verify(processed).insertIfAbsent(KEY, NOW);
    order.verify(stats).incrementTotal("abc123", OCCURRED, NOW);
    order.verify(stats).incrementDaily("abc123", LocalDate.of(2026, 9, 14));
    order.verifyNoMoreInteractions();
  }

  /** Given an already processed key, when applied again, then nothing is incremented (AC-6). */
  @Test
  void duplicateKeyIsNoOpForTotalsLastClickedAndDailyBuckets() {
    when(processed.insertIfAbsent(KEY, NOW)).thenReturn(0);

    Outcome outcome = aggregator.apply(event(KEY, "abc123", OCCURRED));

    assertThat(outcome).isEqualTo(Outcome.DUPLICATE);
    verify(stats, never()).incrementTotal(anyString(), any(), any());
    verify(stats, never()).incrementDaily(anyString(), any());
  }

  /** Given clicks around UTC midnight, when applied, then they land in different UTC days. */
  @Test
  void dayBucketIsDerivedFromOccurredAtInUtcAcrossMidnight() {
    when(processed.insertIfAbsent(anyString(), any())).thenReturn(1);
    Instant beforeMidnight = Instant.parse("2026-09-14T23:59:59.999Z");
    Instant afterMidnight = Instant.parse("2026-09-15T00:00:00Z");
    // 01:30 on the 15th in Berlin (UTC+2) is still 23:30 on the 14th in UTC.
    Instant berlinEarlyMorning =
        ZonedDateTime.of(2026, 9, 15, 1, 30, 0, 0, ZoneId.of("Europe/Berlin")).toInstant();

    aggregator.apply(event("k1", "abc123", beforeMidnight));
    aggregator.apply(event("k2", "abc123", afterMidnight));
    aggregator.apply(event("k3", "abc123", berlinEarlyMorning));

    // beforeMidnight and berlinEarlyMorning both fall on the 14th in UTC; afterMidnight on the 15th.
    verify(stats, times(2)).incrementDaily("abc123", LocalDate.of(2026, 9, 14));
    verify(stats, times(1)).incrementDaily("abc123", LocalDate.of(2026, 9, 15));
    verify(stats).incrementTotal("abc123", berlinEarlyMorning, NOW);
    assertThat(ClickStatsAggregator.utcDay(berlinEarlyMorning))
        .isEqualTo(LocalDate.of(2026, 9, 14));
    assertThat(ClickStatsAggregator.utcDay(afterMidnight)).isEqualTo(LocalDate.of(2026, 9, 15));
  }

  /** Given a failing upsert, when applied, then the failure propagates (transaction rollback). */
  @Test
  void repositoryFailurePropagatesForRetryByCaller() {
    when(processed.insertIfAbsent(KEY, NOW)).thenReturn(1);
    when(stats.incrementTotal(anyString(), any(), any()))
        .thenThrow(new DataAccessResourceFailureException("db down"));

    assertThatThrownBy(() -> aggregator.apply(event(KEY, "abc123", OCCURRED)))
        .isInstanceOf(DataAccessResourceFailureException.class);
    verify(stats, never()).incrementDaily(anyString(), any());
  }

  /** Given any outcome, when logged, then the hashed address and referrer never appear (AC-10). */
  @Test
  void logsNeverCarryHashedIpOrReferrer() {
    when(processed.insertIfAbsent(anyString(), any())).thenReturn(1, 0);

    aggregator.apply(event("k1", "abc123", OCCURRED));
    aggregator.apply(event("k1", "abc123", OCCURRED));

    assertThat(logs.list).isNotEmpty();
    for (ILoggingEvent line : logs.list) {
      assertThat(line.getFormattedMessage())
          .doesNotContain(HASH)
          .doesNotContain("news.example.org")
          .doesNotContain("203.0.113.7");
    }
  }

  /** The unit of work must be transactional so marker and aggregates commit together. */
  @Test
  void applyIsTransactional() throws NoSuchMethodException {
    Transactional annotation =
        ClickStatsAggregator.class
            .getMethod("apply", ClickEventMessage.class)
            .getAnnotation(Transactional.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.readOnly()).isFalse();
  }

  /** Constructor arguments are mandatory. */
  @Test
  void rejectsNullCollaborators() {
    assertThatThrownBy(() -> new ClickStatsAggregator(null, stats))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ClickStatsAggregator(processed, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> aggregator.apply(null)).isInstanceOf(NullPointerException.class);
  }

  private static ClickEventMessage event(String key, String shortCode, Instant occurredAt) {
    return new ClickEventMessage(key, shortCode, occurredAt, HASH, "news.example.org");
  }
}
