/*
 * ClickStatsDayEntry.java — One element of clicks_by_day in the GET /api/v1/urls/{short_code}/stats
 * response: a UTC calendar day and the number of clicks counted on it.
 *
 * Layer: analytics.api (DTO). Built by ClickStatsQueryService from click_stats_daily rows; only
 * days with at least one click are emitted, so the list is sparse (AC-8, AC-9).
 */
package com.example.shortener.analytics.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Clicks of one short code on one UTC day, as rendered in {@code clicks_by_day}.
 *
 * <p>JSON names are the contract's snake_case names ({@code date}, {@code count}); the Java
 * components keep camelCase. Immutable record; created per response, never a bean.
 *
 * @param date the UTC calendar day ({@code YYYY-MM-DD}); never {@code null}
 * @param count clicks counted on that day, {@code >= 1} in every emitted entry
 */
public record ClickStatsDayEntry(
    @JsonProperty("date") LocalDate date, @JsonProperty("count") long count) {

  /**
   * Validates the components.
   *
   * @throws NullPointerException when {@code date} is {@code null}
   * @throws IllegalArgumentException when {@code count} is negative
   */
  public ClickStatsDayEntry {
    Objects.requireNonNull(date, "date");
    if (count < 0) {
      throw new IllegalArgumentException("count must be >= 0");
    }
  }
}
