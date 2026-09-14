/*
 * ClickRetentionJobTest.java — batching, bounding, scheduling and logging of the retention job
 *
 * Layer: test (unit). No Spring context, no database: JdbcTemplate and the transaction manager are
 * mocked. Pins for task T6 (AC-12) that the cutoff is now - analytics.retention.days, that each
 * batch is one bounded DELETE in its own transaction, that a run stops on a short batch or after
 * max-batches-per-run, that the aggregate tables are never named in any statement, that a failing
 * run is contained, that the job is cron-scheduled on the analytics scheduler, and that the log
 * carries counts only.
 */
package com.example.shortener.analytics.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.shortener.analytics.config.AnalyticsProperties;
import com.example.shortener.analytics.outbox.OutboxSchedulingConfig;
import com.example.shortener.analytics.retention.ClickRetentionJob.RetentionSummary;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Unit tests of {@link ClickRetentionJob}. */
class ClickRetentionJobTest {

  private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");
  private static final OffsetDateTime CUTOFF =
      NOW.minus(Duration.ofDays(90)).atOffset(ZoneOffset.UTC);

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
  private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(ClickRetentionJob.class);

  @BeforeEach
  void attachLogAppender() {
    when(txManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
    logEvents.start();
    logger.addAppender(logEvents);
  }

  @AfterEach
  void detachLogAppender() {
    logger.detachAppender(logEvents);
    logEvents.stop();
  }

  /** The cutoff is now minus the configured retention period, and 90 days by default. */
  @Test
  void cutoffIsNowMinusRetentionDays() {
    assertThat(job(90, 1_000, 100).cutoff()).isEqualTo(NOW.minus(Duration.ofDays(90)));
    assertThat(job(30, 1_000, 100).cutoff()).isEqualTo(NOW.minus(Duration.ofDays(30)));
    assertThat(AnalyticsProperties.DEFAULT_RETENTION_DAYS).isEqualTo(90);
  }

  /** Each batch is one bounded DELETE per table, in its own transaction, until a short batch. */
  @Test
  void deletesInBoundedBatchesUntilAShortBatch() {
    when(jdbc.update(eq(ClickRetentionJob.DELETE_OUTBOX_SQL), eq(CUTOFF), eq(3)))
        .thenReturn(3, 3, 1);
    when(jdbc.update(eq(ClickRetentionJob.DELETE_PROCESSED_SQL), eq(CUTOFF), eq(3)))
        .thenReturn(3, 0);

    RetentionSummary summary = job(90, 3, 100).purge();

    assertThat(summary).isEqualTo(new RetentionSummary(CUTOFF.toInstant(), 7L, 3L));
    assertThat(summary.totalDeleted()).isEqualTo(10);
    verify(jdbc, times(3)).update(eq(ClickRetentionJob.DELETE_OUTBOX_SQL), eq(CUTOFF), eq(3));
    verify(jdbc, times(2)).update(eq(ClickRetentionJob.DELETE_PROCESSED_SQL), eq(CUTOFF), eq(3));
    verify(txManager, times(5)).getTransaction(any(TransactionDefinition.class));
    verify(txManager, times(5)).commit(any());
    verify(txManager, never()).rollback(any());
  }

  /** A run never issues more than max-batches-per-run statements per table. */
  @Test
  void runIsBoundedByMaxBatches() {
    when(jdbc.update(anyString(), eq(CUTOFF), eq(2))).thenReturn(2);

    RetentionSummary summary = job(90, 2, 4).purge();

    assertThat(summary.outboxDeleted()).isEqualTo(8);
    assertThat(summary.processedDeleted()).isEqualTo(8);
    verify(jdbc, times(4)).update(eq(ClickRetentionJob.DELETE_OUTBOX_SQL), eq(CUTOFF), eq(2));
    verify(jdbc, times(4)).update(eq(ClickRetentionJob.DELETE_PROCESSED_SQL), eq(CUTOFF), eq(2));
  }

  /** Only the two raw tables are ever named; the LIMIT bound is part of every statement. */
  @Test
  void statementsTouchOnlyRawTablesAndAreBounded() {
    when(jdbc.update(anyString(), eq(CUTOFF), eq(10))).thenReturn(0);

    job(90, 10, 1).purge();

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc, times(2)).update(sql.capture(), eq(CUTOFF), eq(10));
    assertThat(sql.getAllValues()).hasSize(2);
    for (String statement : sql.getAllValues()) {
      assertThat(statement).startsWith("DELETE FROM ").contains(" LIMIT ?");
      assertThat(statement).doesNotContain("click_stats");
    }
    assertThat(sql.getAllValues().get(0)).contains("click_outbox").contains("occurred_at < ?");
    assertThat(sql.getAllValues().get(1))
        .contains("processed_click_event")
        .contains("processed_at < ?");
  }

  /** A failing batch is logged with the partial counts and swallowed; the schedule survives. */
  @Test
  void failingRunIsContained() {
    when(jdbc.update(eq(ClickRetentionJob.DELETE_OUTBOX_SQL), eq(CUTOFF), eq(5))).thenReturn(2);
    when(jdbc.update(eq(ClickRetentionJob.DELETE_PROCESSED_SQL), eq(CUTOFF), eq(5)))
        .thenThrow(new IllegalStateException("database unavailable"));

    RetentionSummary summary = job(90, 5, 100).purge();

    assertThat(summary).isEqualTo(new RetentionSummary(CUTOFF.toInstant(), 2L, 0L));
    verify(txManager).rollback(any());
    assertThat(logEvents.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.ERROR);
    assertThat(logEvents.list.getFirst().getFormattedMessage())
        .contains("deletedOutbox=2")
        .contains("deletedProcessed=0")
        .contains("database unavailable");
  }

  /** The run logs the deleted counts and the cutoff, and nothing else about the rows. */
  @Test
  void logsDeletedCountsPerRunAndNoPayloads() {
    when(jdbc.update(eq(ClickRetentionJob.DELETE_OUTBOX_SQL), eq(CUTOFF), eq(1_000)))
        .thenReturn(42);
    when(jdbc.update(eq(ClickRetentionJob.DELETE_PROCESSED_SQL), eq(CUTOFF), eq(1_000)))
        .thenReturn(7);

    job(90, 1_000, 100).purge();

    List<ILoggingEvent> infos =
        logEvents.list.stream().filter(e -> e.getLevel() == Level.INFO).toList();
    assertThat(infos).hasSize(1);
    String message = infos.getFirst().getFormattedMessage();
    assertThat(message)
        .contains("deletedOutbox=42")
        .contains("deletedProcessed=7")
        .contains("retentionDays=90")
        .contains("cutoff=" + CUTOFF.toInstant());
    assertThat(message)
        .doesNotContain("short_code")
        .doesNotContain("hashed_ip")
        .doesNotContain("referrer")
        .doesNotContain("idempotency_key")
        .doesNotContain("DELETE");
    assertThat(infos.getFirst().getArgumentArray())
        .as("only numbers and the cutoff instant are passed to the logger")
        .allMatch(arg -> arg instanceof Number || arg instanceof Instant);
  }

  /** purge() is cron-scheduled in UTC on the dedicated analytics scheduler. */
  @Test
  void purgeIsCronScheduledOnTheAnalyticsScheduler() throws NoSuchMethodException {
    Method purge = ClickRetentionJob.class.getMethod("purge");
    Scheduled scheduled = purge.getAnnotation(Scheduled.class);

    assertThat(scheduled).isNotNull();
    assertThat(scheduled.scheduler()).isEqualTo(OutboxSchedulingConfig.SCHEDULER_BEAN);
    assertThat(scheduled.cron())
        .contains(ClickRetentionJob.CRON_PROPERTY)
        .contains(ClickRetentionJob.DEFAULT_CRON);
    assertThat(scheduled.zone()).isEqualTo("UTC");
    assertThat(scheduled.fixedDelay()).isEqualTo(-1L);
    assertThat(scheduled.fixedRate()).isEqualTo(-1L);
    assertThat(ClickRetentionJob.DEFAULT_CRON).isEqualTo("0 0 3 * * *");
  }

  /** Bounds must be positive and collaborators mandatory. */
  @Test
  void rejectsInvalidConfiguration() {
    assertThatThrownBy(() -> job(90, 0, 100))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(ClickRetentionJob.BATCH_SIZE_PROPERTY);
    assertThatThrownBy(() -> job(90, 10, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(ClickRetentionJob.MAX_BATCHES_PROPERTY);
    assertThatThrownBy(
            () -> new ClickRetentionJob(null, txManager, properties(90), 10, 10, Clock.systemUTC()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ClickRetentionJob(jdbc, null, properties(90), 10, 10))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ClickRetentionJob(jdbc, txManager, null, 10, 10))
        .isInstanceOf(NullPointerException.class);
  }

  private ClickRetentionJob job(int retentionDays, int batchSize, int maxBatches) {
    return new ClickRetentionJob(
        jdbc,
        txManager,
        properties(retentionDays),
        batchSize,
        maxBatches,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static AnalyticsProperties properties(int retentionDays) {
    return new AnalyticsProperties(
        "unit-test-salt-of-sixteen-chars",
        new AnalyticsProperties.Outbox(500, 100),
        new AnalyticsProperties.Retry(5, 100, 10_000, AnalyticsProperties.Jitter.FULL),
        new AnalyticsProperties.Kafka(5_000),
        new AnalyticsProperties.Retention(retentionDays));
  }
}
