/*
 * ClickStatsDaily.java — JPA entity for one row of click_stats_daily (per-link, per-UTC-day count)
 *
 * Layer: analytics.domain (leaf). Maintained by the Kafka consumer, read by the stats endpoint for
 * the daily breakdown. Mapped 1:1 onto click_stats_daily from V2__click_analytics.sql; the
 * composite primary key (short_code, day) is the embedded Key (AC-7, AC-10).
 */
package com.example.shortener.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Click count of one short code on one UTC calendar day ({@code click_stats_daily}).
 *
 * <p><b>Invariants.</b> {@code key} (short code and day) is non-null; {@code count >= 0} ({@code
 * click_stats_daily_count_chk}). The day is the UTC date of the click's {@code occurred_at}; the
 * consumer derives it with {@code occurredAt.atZone(ZoneOffset.UTC).toLocalDate()}.
 *
 * <p><b>Identity.</b> By {@link Key}; constant {@link #hashCode()}. Not thread-safe; not a Spring
 * bean. Concurrent increments normally go through the atomic upsert on {@code
 * ClickStatsRepository}.
 */
@Entity
@Table(name = "click_stats_daily")
public class ClickStatsDaily {

  /** Composite primary key {@code (short_code, day)}. */
  @EmbeddedId private Key key;

  /** Clicks counted on that day. */
  @Column(name = "count", nullable = false)
  private long count;

  /** Required by JPA. */
  protected ClickStatsDaily() {}

  /**
   * Creates a daily row.
   *
   * @param key short code and UTC day
   * @param count clicks on that day, {@code >= 0}
   * @throws NullPointerException when {@code key} is {@code null}
   * @throws IllegalArgumentException when {@code count} is negative
   */
  public ClickStatsDaily(Key key, long count) {
    this.key = Objects.requireNonNull(key, "key");
    if (count < 0) {
      throw new IllegalArgumentException("count must be >= 0");
    }
    this.count = count;
  }

  /** Counts one more click on this day. */
  public void increment() {
    this.count++;
  }

  /** The composite key. */
  public Key getKey() {
    return key;
  }

  /** Convenience accessor for {@code key.shortCode}. */
  public String getShortCode() {
    return key.getShortCode();
  }

  /** Convenience accessor for {@code key.day}. */
  public LocalDate getDay() {
    return key.getDay();
  }

  /** Clicks counted on this day. */
  public long getCount() {
    return count;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ClickStatsDaily that)) {
      return false;
    }
    return key != null && key.equals(that.key);
  }

  @Override
  public int hashCode() {
    return ClickStatsDaily.class.hashCode();
  }

  @Override
  public String toString() {
    return "ClickStatsDaily{" + key + ", count=" + count + '}';
  }

  /**
   * Composite primary key of {@code click_stats_daily}: {@code (short_code, day)}. Immutable value
   * type; {@link #equals(Object)} and {@link #hashCode()} use both components as JPA requires.
   */
  @Embeddable
  public static class Key implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The short code the clicks belong to. */
    @Column(name = "short_code", nullable = false, length = 32)
    private String shortCode;

    /** UTC calendar day of the clicks. */
    @Column(name = "day", nullable = false)
    private LocalDate day;

    /** Required by JPA. */
    protected Key() {}

    /**
     * Creates a key.
     *
     * @param shortCode the short code
     * @param day UTC calendar day
     * @throws NullPointerException when either argument is {@code null}
     */
    public Key(String shortCode, LocalDate day) {
      this.shortCode = Objects.requireNonNull(shortCode, "shortCode");
      this.day = Objects.requireNonNull(day, "day");
    }

    /** The short code. */
    public String getShortCode() {
      return shortCode;
    }

    /** The UTC day. */
    public LocalDate getDay() {
      return day;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Key that)) {
        return false;
      }
      return Objects.equals(shortCode, that.shortCode) && Objects.equals(day, that.day);
    }

    @Override
    public int hashCode() {
      return Objects.hash(shortCode, day);
    }

    @Override
    public String toString() {
      return "shortCode='" + shortCode + "', day=" + day;
    }
  }
}
