/*
 * ClickStats.java — JPA entity for one row of the click_stats table (per-link click aggregate)
 *
 * Layer: analytics.domain (leaf). Maintained by the Kafka consumer, read by the stats endpoint.
 * Mapped 1:1 onto click_stats from V2__click_analytics.sql (AC-7, AC-10).
 */
package com.example.shortener.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * Total click count of one short code ({@code click_stats}).
 *
 * <p><b>Invariants.</b> {@code shortCode} (primary key) and {@code updatedAt} are non-null; {@code
 * totalClicks >= 0} ({@code click_stats_total_clicks_chk}); {@code lastClickedAt} is {@code null}
 * only for a row that has never counted a click.
 *
 * <p><b>Identity.</b> By {@code shortCode}; constant {@link #hashCode()}. Not thread-safe; not a
 * Spring bean. Concurrent increments are normally done with the atomic upsert on {@code
 * ClickStatsRepository} rather than by loading and saving this entity.
 */
@Entity
@Table(name = "click_stats")
public class ClickStats {

  /** Primary key: the short code the clicks belong to. */
  @Id
  @Column(name = "short_code", nullable = false, length = 32)
  private String shortCode;

  /** Lifetime click count. */
  @Column(name = "total_clicks", nullable = false)
  private long totalClicks;

  /** Instant of the most recent counted click (UTC), or {@code null} when none. */
  @Column(name = "last_clicked_at")
  private Instant lastClickedAt;

  /** When the row was last written (UTC). */
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** Required by JPA. */
  protected ClickStats() {}

  /**
   * Creates an aggregate row.
   *
   * @param shortCode the short code
   * @param totalClicks lifetime click count, {@code >= 0}
   * @param lastClickedAt most recent click (UTC) or {@code null}
   * @param updatedAt write instant (UTC)
   * @throws NullPointerException when {@code shortCode} or {@code updatedAt} is {@code null}
   * @throws IllegalArgumentException when {@code totalClicks} is negative
   */
  public ClickStats(String shortCode, long totalClicks, Instant lastClickedAt, Instant updatedAt) {
    this.shortCode = Objects.requireNonNull(shortCode, "shortCode");
    if (totalClicks < 0) {
      throw new IllegalArgumentException("totalClicks must be >= 0");
    }
    this.totalClicks = totalClicks;
    this.lastClickedAt = lastClickedAt;
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
  }

  /**
   * Counts one click: increments the total and moves {@code lastClickedAt} forward if the click is
   * newer than the one already recorded (events may arrive out of order).
   *
   * @param clickedAt when the click happened (UTC)
   * @param now write instant (UTC)
   */
  public void recordClick(Instant clickedAt, Instant now) {
    Objects.requireNonNull(clickedAt, "clickedAt");
    this.totalClicks++;
    if (lastClickedAt == null || clickedAt.isAfter(lastClickedAt)) {
      this.lastClickedAt = clickedAt;
    }
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  /** The short code (primary key). */
  public String getShortCode() {
    return shortCode;
  }

  /** Lifetime click count. */
  public long getTotalClicks() {
    return totalClicks;
  }

  /** Most recent counted click (UTC), or {@code null}. */
  public Instant getLastClickedAt() {
    return lastClickedAt;
  }

  /** Last write instant (UTC). */
  public Instant getUpdatedAt() {
    return updatedAt;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ClickStats that)) {
      return false;
    }
    return shortCode != null && shortCode.equals(that.shortCode);
  }

  @Override
  public int hashCode() {
    return ClickStats.class.hashCode();
  }

  @Override
  public String toString() {
    return "ClickStats{shortCode='"
        + shortCode
        + "', totalClicks="
        + totalClicks
        + ", lastClickedAt="
        + lastClickedAt
        + ", updatedAt="
        + updatedAt
        + '}';
  }
}
