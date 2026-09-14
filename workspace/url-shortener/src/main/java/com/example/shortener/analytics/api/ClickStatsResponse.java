/*
 * ClickStatsResponse.java — JSON body of GET /api/v1/urls/{short_code}/stats (200)
 *
 * Layer: analytics.api (DTO). Built by ClickStatsQueryService, rendered by ClickStatsController,
 * described as the ClickStatsResponse schema in openapi.yaml (kept in sync by OpenApiContractIT).
 * Serves AC-8 (aggregated stats), AC-9 (zero-click links) and AC-15 (contract).
 */
package com.example.shortener.analytics.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Aggregated click statistics of one short code.
 *
 * <p>JSON names are the contract's snake_case names; Java components keep camelCase. {@code
 * clicksByDay} is copied into an unmodifiable list, so the record is deeply immutable. Created per
 * response, never a bean.
 *
 * @param totalClicks lifetime click count, {@code >= 0}; {@code 0} for a link never clicked
 * @param lastClickedAt instant of the most recent counted click (UTC) or {@code null} when none
 * @param asOf freshness of the numbers: {@code click_stats.updated_at} when an aggregate row
 *     exists, otherwise the service's "now"
 * @param clicksByDay sparse ascending daily counts over the last 30 UTC days (days with at least
 *     one click only); empty when none
 */
public record ClickStatsResponse(
    @JsonProperty("total_clicks") long totalClicks,
    @JsonProperty("last_clicked_at") Instant lastClickedAt,
    @JsonProperty("as_of") Instant asOf,
    @JsonProperty("clicks_by_day") List<ClickStatsDayEntry> clicksByDay) {

  /**
   * Validates the components and freezes the list.
   *
   * @throws NullPointerException when {@code asOf} or {@code clicksByDay} is {@code null}
   * @throws IllegalArgumentException when {@code totalClicks} is negative
   */
  public ClickStatsResponse {
    Objects.requireNonNull(asOf, "asOf");
    Objects.requireNonNull(clicksByDay, "clicksByDay");
    if (totalClicks < 0) {
      throw new IllegalArgumentException("totalClicks must be >= 0");
    }
    clicksByDay = List.copyOf(clicksByDay);
  }
}
