/*
 * ClickAnalyticsRepositoryIT.java — the click-analytics repositories against a real PostgreSQL.
 *
 * Layer: test (integration). Reuses the booted application and Testcontainers fixture of
 * AbstractIntegrationTest (Flyway applies V1 + V2 on start-up, Hibernate validates the entities
 * against the schema) and proves, for task T2 (AC-1, AC-6, AC-7, AC-10, AC-12): the migration
 * applied (all four tables queryable, indexes present), the UNIQUE idempotency_key is enforced,
 * claimBatch returns at most batchSize rows, skips rows locked by a concurrent transaction and
 * ignores non-due or non-pending rows, the aggregate upserts are idempotent-per-event and the
 * dedupe insert returns 0 on a replay. Requires Docker; run by failsafe under ./mvnw -Pit verify.
 */
package com.example.shortener.analytics.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.shortener.analytics.domain.ClickOutboxEntry;
import com.example.shortener.analytics.domain.ClickStats;
import com.example.shortener.analytics.domain.ClickStatsDaily;
import com.example.shortener.analytics.domain.OutboxStatus;
import com.example.shortener.it.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Repository tests for the click-analytics tables against PostgreSQL 17 (Testcontainers).
 *
 * <p>Fixture strategy: the shared application context from {@link AbstractIntegrationTest}; every
 * test starts from empty analytics tables ({@link #resetAnalyticsTables()}). Locking behaviour is
 * exercised with two explicit transactions on two threads via {@link TransactionTemplate}.
 */
class ClickAnalyticsRepositoryIT extends AbstractIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-09-14T12:00:00Z");
  private static final AtomicInteger KEYS = new AtomicInteger();

  @Autowired private ClickOutboxRepository outbox;
  @Autowired private ClickStatsRepository stats;
  @Autowired private ProcessedClickEventRepository processed;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager txManager;

  @BeforeEach
  void resetAnalyticsTables() {
    jdbc.update("DELETE FROM click_outbox");
    jdbc.update("DELETE FROM click_stats_daily");
    jdbc.update("DELETE FROM click_stats");
    jdbc.update("DELETE FROM processed_click_event");
  }

  /** Given the booted app, when the schema is inspected, then V2 applied with its indexes. */
  @Test
  void migrationCreatesTablesAndIndexes() {
    Integer version =
        jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '2' AND success",
            Integer.class);
    assertThat(version).isEqualTo(1);
    for (String table :
        List.of("click_outbox", "click_stats", "click_stats_daily", "processed_click_event")) {
      assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)).isZero();
    }
    List<String> indexDefs =
        jdbc.queryForList(
            "SELECT indexdef FROM pg_indexes WHERE tablename = 'click_outbox'", String.class);
    assertThat(indexDefs)
        .anyMatch(d -> d.contains("(status, next_attempt_at)"))
        .anyMatch(d -> d.contains("(occurred_at)"));
    List<String> columns =
        jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_name IN"
                + " ('click_outbox','click_stats','click_stats_daily','processed_click_event')",
            String.class);
    assertThat(columns.stream().filter(c -> c.contains("ip")).toList())
        .containsExactly("hashed_ip");
  }

  /** Given an entry, when saved and reloaded, then every column round-trips (AC-1). */
  @Test
  void savesAndReloadsAnOutboxEntry() {
    ClickOutboxEntry saved = outbox.saveAndFlush(entry("abc123", T0, "example.org"));
    assertThat(saved.getId()).isNotNull();

    ClickOutboxEntry reloaded =
        outbox.findByIdempotencyKey(saved.getIdempotencyKey()).orElseThrow();
    assertThat(reloaded.getShortCode()).isEqualTo("abc123");
    assertThat(reloaded.getOccurredAt()).isEqualTo(T0);
    assertThat(reloaded.getHashedIp()).hasSize(64);
    assertThat(reloaded.getReferrerHost()).isEqualTo("example.org");
    assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PENDING);
    assertThat(reloaded.getAttempts()).isZero();
    assertThat(reloaded.getNextAttemptAt()).isEqualTo(T0);
    assertThat(reloaded.getPublishedAt()).isNull();
    assertThat(outbox.existsByIdempotencyKey(saved.getIdempotencyKey())).isTrue();
  }

  /** Given a stored key, when a second row with the same key is flushed, then it is rejected. */
  @Test
  void uniqueIdempotencyKeyIsEnforced() {
    ClickOutboxEntry first = outbox.saveAndFlush(entry("abc123", T0, null));
    ClickOutboxEntry duplicate =
        new ClickOutboxEntry(
            first.getIdempotencyKey(), "other1", T0.plusSeconds(1), hash("dup"), null, T0);

    assertThatThrownBy(() -> outbox.saveAndFlush(duplicate))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(outbox.count()).isEqualTo(1);
  }

  /**
   * Given 5 due rows, when claiming with batchSize 2, then exactly 2 are returned, oldest first.
   */
  @Test
  void claimBatchReturnsAtMostBatchSizeRowsOldestDueFirst() {
    for (int i = 0; i < 5; i++) {
      outbox.save(entry("code" + i, T0.plusSeconds(i), null));
    }
    outbox.flush();

    List<ClickOutboxEntry> claimed =
        inTransaction(() -> outbox.claimBatch(T0.plus(Duration.ofHours(1)), 2));

    assertThat(claimed).hasSize(2);
    assertThat(claimed)
        .extracting(ClickOutboxEntry::getShortCode)
        .containsExactly("code0", "code1");
  }

  /** Given rows locked by another transaction, when claiming, then only unlocked rows come back. */
  @Test
  void claimBatchSkipsRowsLockedByAConcurrentTransaction() throws Exception {
    for (int i = 0; i < 5; i++) {
      outbox.save(entry("code" + i, T0.plusSeconds(i), null));
    }
    outbox.flush();
    Instant now = T0.plus(Duration.ofHours(1));

    CountDownLatch claimedByA = new CountDownLatch(1);
    CountDownLatch releaseA = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<List<ClickOutboxEntry>> holder =
          pool.submit(
              () ->
                  inTransaction(
                      () -> {
                        List<ClickOutboxEntry> rows = outbox.claimBatch(now, 3);
                        claimedByA.countDown();
                        await(releaseA);
                        return rows;
                      }));
      assertThat(claimedByA.await(30, TimeUnit.SECONDS)).as("transaction A claimed").isTrue();

      List<ClickOutboxEntry> claimedByB = inTransaction(() -> outbox.claimBatch(now, 10));

      releaseA.countDown();
      List<ClickOutboxEntry> rowsA = holder.get(30, TimeUnit.SECONDS);

      assertThat(rowsA).hasSize(3);
      assertThat(claimedByB).hasSize(2);
      assertThat(claimedByB)
          .extracting(ClickOutboxEntry::getIdempotencyKey)
          .doesNotContainAnyElementsOf(
              rowsA.stream().map(ClickOutboxEntry::getIdempotencyKey).toList());
    } finally {
      pool.shutdownNow();
    }
  }

  /** Given future-dated, published and failed rows, when claiming, then none is returned. */
  @Test
  void claimBatchIgnoresNotDueAndTerminalRows() {
    ClickOutboxEntry due = outbox.save(entry("due000", T0, null));
    outbox.save(entry("later0", T0.plus(Duration.ofDays(1)), null));
    ClickOutboxEntry published = entry("pub000", T0, null);
    published.markPublished(T0.plusSeconds(1));
    outbox.save(published);
    ClickOutboxEntry failed = entry("fail00", T0, null);
    failed.markFailed();
    outbox.save(failed);
    outbox.flush();

    List<ClickOutboxEntry> claimed = inTransaction(() -> outbox.claimBatch(T0, 100));

    assertThat(claimed)
        .extracting(ClickOutboxEntry::getIdempotencyKey)
        .containsExactly(due.getIdempotencyKey());
    assertThat(outbox.countByStatus(OutboxStatus.PENDING)).isEqualTo(2);
    assertThat(outbox.countByStatus(OutboxStatus.PUBLISHED)).isEqualTo(1);
    assertThat(outbox.countByStatus(OutboxStatus.FAILED)).isEqualTo(1);
  }

  /** Given old and recent rows, when purging, then only rows older than the cutoff go (AC-12). */
  @Test
  void retentionPurgeDeletesOnlyRowsOlderThanCutoff() {
    outbox.save(entry("old000", T0.minus(Duration.ofDays(91)), null));
    outbox.save(entry("new000", T0.minus(Duration.ofDays(1)), null));
    outbox.flush();

    int deleted = inTransaction(() -> outbox.deleteOccurredBefore(T0.minus(Duration.ofDays(90))));

    assertThat(deleted).isEqualTo(1);
    assertThat(outbox.findAll())
        .extracting(ClickOutboxEntry::getShortCode)
        .containsExactly("new000");
  }

  /** Given repeated increments, when read back, then totals and daily rows are upserted (AC-7). */
  @Test
  void statsUpsertsCreateThenIncrementAggregates() {
    Instant later = T0.plus(Duration.ofHours(2));
    LocalDate day = T0.atZone(ZoneOffset.UTC).toLocalDate();

    inTransaction(() -> stats.incrementTotal("abc123", later, later));
    inTransaction(() -> stats.incrementTotal("abc123", T0, later.plusSeconds(1)));
    inTransaction(() -> stats.incrementDaily("abc123", day));
    inTransaction(() -> stats.incrementDaily("abc123", day));
    inTransaction(() -> stats.incrementDaily("abc123", day.plusDays(1)));

    ClickStats total = stats.findById("abc123").orElseThrow();
    assertThat(total.getTotalClicks()).isEqualTo(2);
    assertThat(total.getLastClickedAt())
        .as("out-of-order click does not move it back")
        .isEqualTo(later);

    List<ClickStatsDaily> daily = stats.findDaily("abc123", day.minusDays(7), day.plusDays(7));
    assertThat(daily).extracting(ClickStatsDaily::getDay).containsExactly(day, day.plusDays(1));
    assertThat(daily).extracting(ClickStatsDaily::getCount).containsExactly(2L, 1L);
    assertThat(stats.findDaily("abc123", day.plusDays(2), day.plusDays(9))).isEmpty();
  }

  /** Given a processed key, when inserted again, then 0 rows are affected (AC-6 dedupe). */
  @Test
  void processedEventInsertIfAbsentIsIdempotent() {
    int first = inTransaction(() -> processed.insertIfAbsent("evt-1", T0));
    int replay = inTransaction(() -> processed.insertIfAbsent("evt-1", T0.plusSeconds(5)));

    assertThat(first).isEqualTo(1);
    assertThat(replay).isZero();
    assertThat(processed.findById("evt-1").orElseThrow().getProcessedAt()).isEqualTo(T0);
    int purged = inTransaction(() -> processed.deleteProcessedBefore(T0.plusSeconds(1)));
    assertThat(purged).isEqualTo(1);
    assertThat(processed.count()).isZero();
  }

  private static ClickOutboxEntry entry(String shortCode, Instant occurredAt, String referrer) {
    String key = "it-" + KEYS.incrementAndGet() + "-" + System.nanoTime();
    return new ClickOutboxEntry(key, shortCode, occurredAt, hash(key), referrer, occurredAt);
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

  private <T> T inTransaction(java.util.function.Supplier<T> work) {
    return new TransactionTemplate(txManager).execute(status -> work.get());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("latch not released in time");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
