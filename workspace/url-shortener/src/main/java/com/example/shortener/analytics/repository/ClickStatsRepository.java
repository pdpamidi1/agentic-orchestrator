/*
 * ClickStatsRepository.java — Spring Data JPA repository for the click_stats aggregate plus the
 * atomic upserts of click_stats and click_stats_daily
 *
 * Layer: analytics.repository (depends on analytics.domain only). The consumer increments the
 * aggregates through the two native upserts; the stats endpoint reads through findById and
 * findDaily (AC-7, AC-10).
 */
package com.example.shortener.analytics.repository;

import com.example.shortener.analytics.domain.ClickStats;
import com.example.shortener.analytics.domain.ClickStatsDaily;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link ClickStats}, also hosting the {@link ClickStatsDaily}
 * queries so the consumer and the API deal with one data-access type for aggregates.
 *
 * <p><b>Concurrency.</b> {@link #incrementTotal} and {@link #incrementDaily} are single-statement
 * {@code INSERT ... ON CONFLICT DO UPDATE} upserts, so concurrent consumers for different keys
 * never conflict and same-key increments serialise on the row without lost updates. Singleton proxy
 * bean; safe for concurrent use.
 */
public interface ClickStatsRepository extends JpaRepository<ClickStats, String> {

  /**
   * Atomically adds one click to the lifetime total of a short code, creating the row if needed and
   * moving {@code last_clicked_at} forward only (out-of-order events do not move it back).
   *
   * @param shortCode the short code
   * @param clickedAt when the click happened (UTC)
   * @param now write instant (UTC)
   * @return number of affected rows (1)
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "INSERT INTO click_stats (short_code, total_clicks, last_clicked_at, updated_at) VALUES"
              + " (:shortCode, 1, :clickedAt, :now) ON CONFLICT (short_code) DO UPDATE SET"
              + " total_clicks = click_stats.total_clicks + 1, last_clicked_at ="
              + " GREATEST(click_stats.last_clicked_at, EXCLUDED.last_clicked_at), updated_at ="
              + " EXCLUDED.updated_at",
      nativeQuery = true)
  int incrementTotal(
      @Param("shortCode") String shortCode,
      @Param("clickedAt") Instant clickedAt,
      @Param("now") Instant now);

  /**
   * Atomically adds one click to the daily count of a short code on a UTC day, creating the row if
   * needed.
   *
   * @param shortCode the short code
   * @param day UTC calendar day of the click
   * @return number of affected rows (1)
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "INSERT INTO click_stats_daily (short_code, day, count)"
              + " VALUES (:shortCode, :day, 1)"
              + " ON CONFLICT (short_code, day) DO UPDATE SET"
              + " count = click_stats_daily.count + 1",
      nativeQuery = true)
  int incrementDaily(@Param("shortCode") String shortCode, @Param("day") LocalDate day);

  /**
   * Daily counts of a short code within an inclusive day range, oldest first.
   *
   * @param shortCode the short code
   * @param from first day (inclusive, UTC)
   * @param to last day (inclusive, UTC)
   * @return the rows in ascending day order, possibly empty
   */
  @Query(
      "SELECT d FROM ClickStatsDaily d"
          + " WHERE d.key.shortCode = :shortCode AND d.key.day BETWEEN :from AND :to"
          + " ORDER BY d.key.day ASC")
  List<ClickStatsDaily> findDaily(
      @Param("shortCode") String shortCode,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);
}
