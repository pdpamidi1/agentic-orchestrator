/*
 * ClickRetentionJobIT.java — the retention purge against a real PostgreSQL
 *
 * Layer: test (integration). Reuses the booted application and Testcontainers fixture of
 * AbstractIntegrationTest and proves, for task T6 (AC-12): rows straddling the 90-day boundary
 * are purged only when older than the cutoff, in both click_outbox and processed_click_event; the
 * click_stats and click_stats_daily aggregates are byte-for-byte unchanged; a run deletes at most
 * batch-size × max-batches rows and leaves the rest for the next run; and the Spring-managed bean
 * exists with the default bounds. Requires Docker; run by failsafe under ./mvnw -Pit verify.
 */
package com.example.shortener.analytics.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortener.analytics.config.AnalyticsProperties;
import com.example.shortener.analytics.domain.ClickOutboxEntry;
import com.example.shortener.analytics.domain.ProcessedClickEvent;
import com.example.shortener.analytics.repository.ClickOutboxRepository;
import com.example.shortener.analytics.repository.ProcessedClickEventRepository;
import com.example.shortener.analytics.retention.ClickRetentionJob.RetentionSummary;
import com.example.shortener.it.AbstractIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Integration tests of {@link ClickRetentionJob} on PostgreSQL 17 (Testcontainers). */
class ClickRetentionJobIT extends AbstractIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");
  private static final Duration RETENTION = Duration.ofDays(90);
  private static final AtomicInteger KEYS = new AtomicInteger();

  @Autowired private ClickOutboxRepository outbox;
  @Autowired private ProcessedClickEventRepository processed;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager txManager;
  @Autowired private AnalyticsProperties properties;
  @Autowired private ClickRetentionJob managedJob;

  @BeforeEach
  void resetAnalyticsTables() {
    jdbc.update("DELETE FROM click_outbox");
    jdbc.update("DELETE FROM click_stats_daily");
    jdbc.update("DELETE FROM click_stats");
    jdbc.update("DELETE FROM processed_click_event");
  }

  /**
   * Given raw rows on both sides of the 90-day boundary and populated aggregates, when the job
   * runs, then only the older raw rows are gone and the aggregates are untouched (AC-12).
   */
  @Test
  void purgesOnlyRawRowsOlderThanRetentionAndLeavesAggregatesAlone() {
    ClickOutboxEntry oldOutbox = outbox.save(entry("old000", NOW.minus(Duration.ofDays(91))));
    ClickOutboxEntry boundary = outbox.save(entry("edge00", NOW.minus(RETENTION).plusSeconds(1)));
    ClickOutboxEntry recent = outbox.save(entry("new000", NOW.minus(Duration.ofDays(1))));
    outbox.flush();
    processed.save(new ProcessedClickEvent("evt-old", NOW.minus(Duration.ofDays(120))));
    processed.save(new ProcessedClickEvent("evt-edge", NOW.minus(RETENTION).plusSeconds(1)));
    processed.save(new ProcessedClickEvent("evt-new", NOW.minus(Duration.ofHours(1))));
    processed.flush();
    seedAggregates();
    List<Map<String, Object>> statsBefore = snapshot("click_stats");
    List<Map<String, Object>> dailyBefore = snapshot("click_stats_daily");

    RetentionSummary summary = job(1_000, 100).purge();

    assertThat(summary.cutoff()).isEqualTo(NOW.minus(RETENTION));
    assertThat(summary.outboxDeleted()).isEqualTo(1);
    assertThat(summary.processedDeleted()).isEqualTo(1);
    assertThat(outbox.findAll())
        .extracting(ClickOutboxEntry::getIdempotencyKey)
        .containsExactlyInAnyOrder(boundary.getIdempotencyKey(), recent.getIdempotencyKey())
        .doesNotContain(oldOutbox.getIdempotencyKey());
    assertThat(processed.findAll())
        .extracting(ProcessedClickEvent::getIdempotencyKey)
        .containsExactlyInAnyOrder("evt-edge", "evt-new");
    assertThat(snapshot("click_stats")).isEqualTo(statsBefore);
    assertThat(snapshot("click_stats_daily")).isEqualTo(dailyBefore);
    assertThat(statsBefore).hasSize(2);
    assertThat(dailyBefore).hasSize(3);
  }

  /**
   * Given more expired rows than one run may delete, when the job runs, then it deletes exactly
   * batch-size × max-batches oldest rows and the next run finishes the job (AC-12 bounding).
   */
  @Test
  void runIsBoundedByBatchSizeTimesMaxBatches() {
    for (int i = 0; i < 7; i++) {
      outbox.save(entry("old00" + i, NOW.minus(Duration.ofDays(100 + i))));
      processed.save(new ProcessedClickEvent("evt-" + i, NOW.minus(Duration.ofDays(100 + i))));
    }
    outbox.save(entry("new000", NOW.minus(Duration.ofDays(1))));
    processed.save(new ProcessedClickEvent("evt-new", NOW.minus(Duration.ofDays(1))));
    outbox.flush();
    processed.flush();
    ClickRetentionJob bounded = job(2, 2);

    RetentionSummary first = bounded.purge();

    assertThat(first.outboxDeleted()).isEqualTo(4);
    assertThat(first.processedDeleted()).isEqualTo(4);
    assertThat(outbox.count()).isEqualTo(4);
    assertThat(processed.count()).isEqualTo(4);
    assertThat(outbox.findAll())
        .as("the oldest rows go first")
        .extracting(ClickOutboxEntry::getShortCode)
        .containsExactlyInAnyOrder("old000", "old001", "old002", "new000");

    RetentionSummary second = bounded.purge();

    assertThat(second.outboxDeleted()).isEqualTo(3);
    assertThat(second.processedDeleted()).isEqualTo(3);
    assertThat(outbox.findAll())
        .extracting(ClickOutboxEntry::getShortCode)
        .containsExactly("new000");
    assertThat(processed.findAll())
        .extracting(ProcessedClickEvent::getIdempotencyKey)
        .containsExactly("evt-new");
    assertThat(bounded.purge().totalDeleted()).isZero();
  }

  /** The application registers the job with the documented defaults. */
  @Test
  void managedJobUsesConfiguredDefaults() {
    assertThat(managedJob.batchSize()).isEqualTo(ClickRetentionJob.DEFAULT_BATCH_SIZE);
    assertThat(managedJob.maxBatchesPerRun()).isEqualTo(ClickRetentionJob.DEFAULT_MAX_BATCHES);
    assertThat(managedJob.cutoff())
        .isBefore(Instant.now().minus(Duration.ofDays(properties.retention().days() - 1)));
    assertThat(managedJob.purge().totalDeleted()).isZero();
  }

  private ClickRetentionJob job(int batchSize, int maxBatches) {
    return new ClickRetentionJob(
        jdbc, txManager, properties, batchSize, maxBatches, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private void seedAggregates() {
    LocalDate day = NOW.atZone(ZoneOffset.UTC).toLocalDate();
    jdbc.update(
        "INSERT INTO click_stats (short_code, total_clicks, last_clicked_at, updated_at)"
            + " VALUES ('old000', 5, ?, ?), ('new000', 1, ?, ?)",
        ts(NOW.minus(Duration.ofDays(91))),
        ts(NOW.minus(Duration.ofDays(91))),
        ts(NOW),
        ts(NOW));
    jdbc.update(
        "INSERT INTO click_stats_daily (short_code, day, count)"
            + " VALUES ('old000', ?, 3), ('old000', ?, 2), ('new000', ?, 1)",
        day.minusDays(120),
        day.minusDays(91),
        day);
  }

  private List<Map<String, Object>> snapshot(String table) {
    return jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1, 2");
  }

  private static java.time.OffsetDateTime ts(Instant at) {
    return at.atOffset(ZoneOffset.UTC);
  }

  private static ClickOutboxEntry entry(String shortCode, Instant occurredAt) {
    String key = "ret-" + KEYS.incrementAndGet() + "-" + System.nanoTime();
    return new ClickOutboxEntry(key, shortCode, occurredAt, hash(key), null, occurredAt);
  }

  /** A deterministic 64-char lowercase hex string shaped like a SHA-256 digest. */
  private static String hash(String seed) {
    byte[] bytes = new byte[32];
    int h = seed.hashCode();
    for (int i = 0; i < bytes.length; i++) {
      h = h * 31 + i;
      bytes[i] = (byte) h;
    }
    return HexFormat.of().formatHex(bytes);
  }
}
