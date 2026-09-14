/*
 * ClickStatsAggregatorIT.java — the aggregator against a real PostgreSQL through the Spring proxy
 *
 * Layer: test (integration). Reuses the booted application and Testcontainers fixture of
 * AbstractIntegrationTest and proves, with the real ON CONFLICT upserts, for task T5 (AC-5, AC-6,
 * AC-7, AC-10): a single event creates click_stats and the daily bucket; a duplicate delivery
 * changes nothing; out-of-order events never move last_clicked_at backwards while still counting;
 * clicks on both sides of UTC midnight land in two daily rows; the marker and the aggregates are
 * written in one transaction (a failure after the marker leaves no marker behind). Requires
 * Docker; run by failsafe under ./mvnw -Pit verify.
 */
package com.example.shortener.analytics.aggregation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortener.analytics.aggregation.ClickStatsAggregator.Outcome;
import com.example.shortener.analytics.domain.ClickStats;
import com.example.shortener.analytics.domain.ClickStatsDaily;
import com.example.shortener.analytics.kafka.ClickEventMessage;
import com.example.shortener.analytics.repository.ClickStatsRepository;
import com.example.shortener.analytics.repository.ProcessedClickEventRepository;
import com.example.shortener.it.AbstractIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Integration tests of {@link ClickStatsAggregator} on PostgreSQL 17 (Testcontainers). */
class ClickStatsAggregatorIT extends AbstractIntegrationTest {

  private static final String CODE = "aggr01";
  private static final String HASH = "b".repeat(64);
  private static final Instant T1 = Instant.parse("2026-09-14T12:00:00Z");
  private static final Instant T2 = Instant.parse("2026-09-14T12:30:00Z");

  @Autowired private ClickStatsAggregator aggregator;
  @Autowired private ClickStatsRepository stats;
  @Autowired private ProcessedClickEventRepository processed;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void resetAnalyticsTables() {
    jdbc.update("DELETE FROM click_stats_daily");
    jdbc.update("DELETE FROM click_stats");
    jdbc.update("DELETE FROM processed_click_event");
  }

  /** Given an empty table, when one event is applied, then total, last click and day are set. */
  @Test
  void singleEventCreatesAggregateAndDailyBucket() {
    Instant before = Instant.now();

    assertThat(aggregator.apply(event("it-single", T1))).isEqualTo(Outcome.APPLIED);

    ClickStats row = stats.findById(CODE).orElseThrow();
    assertThat(row.getTotalClicks()).isEqualTo(1);
    assertThat(row.getLastClickedAt()).isEqualTo(T1);
    assertThat(row.getUpdatedAt()).isAfterOrEqualTo(before.minusSeconds(1));
    List<ClickStatsDaily> daily =
        stats.findDaily(CODE, LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 14));
    assertThat(daily).hasSize(1);
    assertThat(daily.getFirst().getCount()).isEqualTo(1);
    assertThat(processed.existsById("it-single")).isTrue();
  }

  /** Given an applied key, when redelivered, then totals, last click and day are unchanged. */
  @Test
  void duplicateDeliveryIsNoOp() {
    aggregator.apply(event("it-dup", T1));
    ClickStats first = stats.findById(CODE).orElseThrow();

    assertThat(aggregator.apply(event("it-dup", T2))).isEqualTo(Outcome.DUPLICATE);

    ClickStats again = stats.findById(CODE).orElseThrow();
    assertThat(again.getTotalClicks()).isEqualTo(1);
    assertThat(again.getLastClickedAt()).isEqualTo(first.getLastClickedAt());
    assertThat(again.getUpdatedAt()).isEqualTo(first.getUpdatedAt());
    assertThat(stats.findDaily(CODE, LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 14)))
        .singleElement()
        .satisfies(d -> assertThat(d.getCount()).isEqualTo(1));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_click_event", Long.class))
        .isEqualTo(1L);
  }

  /** Given a newer event already counted, when an older one arrives, then last click stays. */
  @Test
  void outOfOrderEventCountsButNeverMovesLastClickedAtBackwards() {
    aggregator.apply(event("it-new", T2));
    aggregator.apply(event("it-old", T1));

    ClickStats row = stats.findById(CODE).orElseThrow();
    assertThat(row.getTotalClicks()).isEqualTo(2);
    assertThat(row.getLastClickedAt()).isEqualTo(T2);

    aggregator.apply(event("it-newest", T2.plusSeconds(60)));
    assertThat(stats.findById(CODE).orElseThrow().getLastClickedAt()).isEqualTo(T2.plusSeconds(60));
  }

  /** Given clicks on both sides of UTC midnight, when applied, then two daily rows exist. */
  @Test
  void utcMidnightBoundarySplitsDailyBuckets() {
    aggregator.apply(event("it-d1", Instant.parse("2026-09-14T23:59:59Z")));
    aggregator.apply(event("it-d2", Instant.parse("2026-09-15T00:00:00Z")));
    aggregator.apply(event("it-d3", Instant.parse("2026-09-15T00:00:01Z")));

    List<ClickStatsDaily> daily =
        stats.findDaily(CODE, LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 15));
    assertThat(daily).hasSize(2);
    assertThat(daily.get(0).getDay()).isEqualTo(LocalDate.of(2026, 9, 14));
    assertThat(daily.get(0).getCount()).isEqualTo(1);
    assertThat(daily.get(1).getDay()).isEqualTo(LocalDate.of(2026, 9, 15));
    assertThat(daily.get(1).getCount()).isEqualTo(2);
    assertThat(stats.findById(CODE).orElseThrow().getTotalClicks()).isEqualTo(3);
  }

  /** Given a short code longer than the column, when applied, then no marker survives (atomic). */
  @Test
  void failureAfterMarkerRollsBackMarkerToo() {
    String tooLong = "x".repeat(40);
    ClickEventMessage bad = new ClickEventMessage("it-fail", tooLong, T1, HASH, null);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> aggregator.apply(bad))
        .isInstanceOf(RuntimeException.class);

    assertThat(processed.existsById("it-fail")).isFalse();
    assertThat(stats.findById(tooLong)).isEmpty();
  }

  private static ClickEventMessage event(String key, Instant occurredAt) {
    return new ClickEventMessage(key, CODE, occurredAt, HASH, "news.example.org");
  }
}
