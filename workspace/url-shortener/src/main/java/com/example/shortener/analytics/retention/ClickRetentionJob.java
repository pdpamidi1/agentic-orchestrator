/*
 * ClickRetentionJob.java — scheduled purge of raw click rows older than the retention period
 *
 * Layer: analytics.retention (depends on analytics.config and Spring JDBC/tx only). Task T6,
 * AC-12: an in-process job that deletes click_outbox rows whose occurred_at and
 * processed_click_event rows whose processed_at are older than analytics.retention.days
 * (default 90) in configurable bounded batches, each batch in its own short transaction, and
 * never touches the click_stats / click_stats_daily aggregates. Runs on the dedicated analytics
 * scheduler, never on a request thread. Logs only counts, never row payloads.
 */
package com.example.shortener.analytics.retention;

import com.example.shortener.analytics.config.AnalyticsProperties;
import com.example.shortener.analytics.outbox.OutboxSchedulingConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes raw click rows that have aged past {@code analytics.retention.days}.
 *
 * <p><b>What is purged.</b> {@code click_outbox} rows with {@code occurred_at < now - retention}
 * (whatever their relay status: a row that old has either been published, failed for good, or is no
 * longer worth relaying) and {@code processed_click_event} dedupe markers with {@code processed_at
 * < now - retention}. The aggregates {@code click_stats} and {@code click_stats_daily} are the
 * product of those events and are <em>never</em> touched.
 *
 * <p><b>Batching.</b> Each table is purged in batches of at most {@link #batchSize} rows, every
 * batch in its own transaction so that a large backlog never holds a long-running lock or bloats a
 * single transaction. A run stops for a table once a batch comes back short (nothing older is left)
 * or after {@link #maxBatchesPerRun} batches; whatever remains is picked up by the next run.
 *
 * <p><b>Scheduling.</b> {@link #purge()} runs on the cron {@code analytics.retention.cron} (default
 * 03:00 UTC daily) on the single-threaded {@code analyticsTaskScheduler} shared with the outbox
 * relay, so the purge and the relay never run concurrently. A failing run is logged and swallowed;
 * the schedule survives.
 *
 * <p><b>Logging.</b> Only per-run deleted counts and the cutoff are logged. No column value of any
 * deleted row (short code, hashed address, referrer, key) ever reaches a log line.
 *
 * <p><b>Thread-safety.</b> Stateless apart from immutable configuration; safe to invoke from the
 * scheduler and from tests.
 */
@Component
public class ClickRetentionJob {

  private static final Logger log = LoggerFactory.getLogger(ClickRetentionJob.class);

  /** Cron property (6 fields, UTC) driving the job; default 03:00 UTC every day. */
  public static final String CRON_PROPERTY = "analytics.retention.cron";

  /** Default cron expression: 03:00 UTC daily. */
  public static final String DEFAULT_CRON = "0 0 3 * * *";

  /** Property bounding the rows deleted per statement/transaction. */
  public static final String BATCH_SIZE_PROPERTY = "analytics.retention.batch-size";

  /** Default rows per batch. */
  public static final int DEFAULT_BATCH_SIZE = 1_000;

  /** Property bounding the number of batches a single run may issue per table. */
  public static final String MAX_BATCHES_PROPERTY = "analytics.retention.max-batches-per-run";

  /** Default batches per table per run (with the default batch size: 100 000 rows). */
  public static final int DEFAULT_MAX_BATCHES = 100;

  /** Bounded delete of the oldest outbox rows before the cutoff (PostgreSQL). */
  static final String DELETE_OUTBOX_SQL =
      "DELETE FROM click_outbox WHERE id IN ("
          + "SELECT id FROM click_outbox WHERE occurred_at < ? ORDER BY occurred_at, id LIMIT ?)";

  /** Bounded delete of the oldest dedupe markers before the cutoff (PostgreSQL). */
  static final String DELETE_PROCESSED_SQL =
      "DELETE FROM processed_click_event WHERE idempotency_key IN ("
          + "SELECT idempotency_key FROM processed_click_event WHERE processed_at < ?"
          + " ORDER BY processed_at, idempotency_key LIMIT ?)";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final AnalyticsProperties properties;
  private final int batchSize;
  private final int maxBatchesPerRun;
  private final Clock clock;

  /**
   * Spring constructor: binds the batch bounds from configuration and uses the system UTC clock.
   *
   * @param jdbc JDBC access to the analytics tables
   * @param transactionManager transaction manager; one transaction per batch
   * @param properties analytics configuration (retention days)
   * @param batchSize rows per batch, {@code analytics.retention.batch-size}
   * @param maxBatchesPerRun batches per table per run, {@code
   *     analytics.retention.max-batches-per-run}
   */
  @Autowired
  public ClickRetentionJob(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      AnalyticsProperties properties,
      @Value("${" + BATCH_SIZE_PROPERTY + ":" + DEFAULT_BATCH_SIZE + "}") int batchSize,
      @Value("${" + MAX_BATCHES_PROPERTY + ":" + DEFAULT_MAX_BATCHES + "}") int maxBatchesPerRun) {
    this(jdbc, transactionManager, properties, batchSize, maxBatchesPerRun, Clock.systemUTC());
  }

  /**
   * Full constructor (tests inject a fixed clock).
   *
   * @throws IllegalArgumentException when a bound is not positive
   */
  public ClickRetentionJob(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      AnalyticsProperties properties,
      int batchSize,
      int maxBatchesPerRun,
      Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (batchSize < 1) {
      throw new IllegalArgumentException(BATCH_SIZE_PROPERTY + " must be >= 1");
    }
    if (maxBatchesPerRun < 1) {
      throw new IllegalArgumentException(MAX_BATCHES_PROPERTY + " must be >= 1");
    }
    this.batchSize = batchSize;
    this.maxBatchesPerRun = maxBatchesPerRun;
  }

  /**
   * Scheduled entry point: one retention run. Never throws; a failed run is logged with its partial
   * counts and retried at the next cron tick.
   *
   * @return the counts of this run (partial when it failed midway)
   */
  @Scheduled(
      cron = "${" + CRON_PROPERTY + ":" + DEFAULT_CRON + "}",
      zone = "UTC",
      scheduler = OutboxSchedulingConfig.SCHEDULER_BEAN)
  public RetentionSummary purge() {
    Instant cutoff = cutoff();
    long outboxDeleted = 0;
    long processedDeleted = 0;
    try {
      outboxDeleted = purgeTable(DELETE_OUTBOX_SQL, cutoff);
      processedDeleted = purgeTable(DELETE_PROCESSED_SQL, cutoff);
      RetentionSummary summary = new RetentionSummary(cutoff, outboxDeleted, processedDeleted);
      log.info(
          "Click retention run: cutoff={} retentionDays={} deletedOutbox={} deletedProcessed={}"
              + " batchSize={}",
          cutoff,
          properties.retention().days(),
          outboxDeleted,
          processedDeleted,
          batchSize);
      return summary;
    } catch (RuntimeException e) {
      log.error(
          "Click retention run failed after deletedOutbox={} deletedProcessed={}; remaining rows"
              + " are retried at the next run: {}",
          outboxDeleted,
          processedDeleted,
          e.toString());
      return new RetentionSummary(cutoff, outboxDeleted, processedDeleted);
    }
  }

  /** The instant before which raw rows are purged: {@code now - analytics.retention.days}. */
  public Instant cutoff() {
    return clock.instant().minus(properties.retention().period());
  }

  /** Rows per batch. */
  public int batchSize() {
    return batchSize;
  }

  /** Batches per table per run. */
  public int maxBatchesPerRun() {
    return maxBatchesPerRun;
  }

  /**
   * Deletes rows older than {@code cutoff} in batches until a short batch or the per-run bound.
   *
   * @return the number of rows deleted from that table in this run
   */
  private long purgeTable(String sql, Instant cutoff) {
    OffsetDateTime before = OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC);
    long total = 0;
    for (int batch = 0; batch < maxBatchesPerRun; batch++) {
      Integer deleted = transaction.execute(status -> jdbc.update(sql, before, batchSize));
      int count = deleted == null ? 0 : deleted;
      total += count;
      if (count < batchSize) {
        break;
      }
    }
    return total;
  }

  /**
   * Result of one retention run: counts only, no row data.
   *
   * @param cutoff rows older than this were eligible
   * @param outboxDeleted rows removed from {@code click_outbox}
   * @param processedDeleted rows removed from {@code processed_click_event}
   */
  public record RetentionSummary(Instant cutoff, long outboxDeleted, long processedDeleted) {

    /** Rows removed from both tables together. */
    public long totalDeleted() {
      return outboxDeleted + processedDeleted;
    }
  }
}
