/*
 * ClickStatsQueryServiceTest.java — Unit tests for ClickStatsQueryService and the two DTOs
 *
 * Layer: test. Mockito mocks of UrlMappingRepository and ClickStatsRepository plus a fixed clock:
 * 404 for unknown links (AC-14), the 0/null/now/empty shape for never-clicked links (AC-9), the
 * aggregate mapping and the inclusive 30-day UTC window with zero-count filtering (AC-8), and the
 * invariants of ClickStatsResponse / ClickStatsDayEntry.
 */
package com.example.shortener.analytics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.shortener.analytics.domain.ClickStats;
import com.example.shortener.analytics.domain.ClickStatsDaily;
import com.example.shortener.analytics.repository.ClickStatsRepository;
import com.example.shortener.api.error.ShortCodeNotFoundException;
import com.example.shortener.domain.UrlMappingRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@link ClickStatsQueryService} over mocked repositories and a fixed UTC clock. */
class ClickStatsQueryServiceTest {

  private static final String CODE = "abc123";
  private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");

  private final UrlMappingRepository urls = mock(UrlMappingRepository.class);
  private final ClickStatsRepository stats = mock(ClickStatsRepository.class);
  private final ClickStatsQueryService service =
      new ClickStatsQueryService(urls, stats, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void unknownShortCodeThrowsNotFoundWithoutReadingAggregates() {
    when(urls.existsByShortCode(CODE)).thenReturn(false);

    assertThatThrownBy(() -> service.stats(CODE))
        .isInstanceOf(ShortCodeNotFoundException.class)
        .hasMessageContaining(CODE);
    verifyNoInteractions(stats);
  }

  @Test
  void neverClickedLinkYieldsZeroNullNowAndEmptyList() {
    when(urls.existsByShortCode(CODE)).thenReturn(true);
    when(stats.findById(CODE)).thenReturn(Optional.empty());
    when(stats.findDaily(eq(CODE), any(), any())).thenReturn(List.of());

    ClickStatsResponse response = service.stats(CODE);

    assertThat(response.totalClicks()).isZero();
    assertThat(response.lastClickedAt()).isNull();
    assertThat(response.asOf()).isEqualTo(NOW);
    assertThat(response.clicksByDay()).isEmpty();
  }

  @Test
  void aggregateRowSuppliesTotalLastClickAndAsOf() {
    Instant last = Instant.parse("2026-09-10T00:00:00Z");
    Instant updated = Instant.parse("2026-09-10T00:00:01Z");
    when(urls.existsByShortCode(CODE)).thenReturn(true);
    when(stats.findById(CODE)).thenReturn(Optional.of(new ClickStats(CODE, 7, last, updated)));
    when(stats.findDaily(eq(CODE), any(), any())).thenReturn(List.of(daily("2026-09-10", 7)));

    ClickStatsResponse response = service.stats(CODE);

    assertThat(response.totalClicks()).isEqualTo(7);
    assertThat(response.lastClickedAt()).isEqualTo(last);
    assertThat(response.asOf()).isEqualTo(updated);
    assertThat(response.clicksByDay())
        .containsExactly(new ClickStatsDayEntry(LocalDate.parse("2026-09-10"), 7));
  }

  @Test
  void windowIsTodayMinus29ToTodayInUtcAndZeroCountDaysAreDropped() {
    when(urls.existsByShortCode(CODE)).thenReturn(true);
    when(stats.findById(CODE)).thenReturn(Optional.empty());
    when(stats.findDaily(eq(CODE), any(), any()))
        .thenReturn(
            List.of(daily("2026-08-16", 2), daily("2026-08-17", 0), daily("2026-09-14", 1)));

    ClickStatsResponse response = service.stats(CODE);

    verify(stats).findDaily(CODE, LocalDate.parse("2026-08-16"), LocalDate.parse("2026-09-14"));
    assertThat(response.clicksByDay())
        .extracting(ClickStatsDayEntry::date)
        .containsExactly(LocalDate.parse("2026-08-16"), LocalDate.parse("2026-09-14"));
  }

  @Test
  void windowEndIsTheUtcDayEvenWhenTheClockZoneDiffers() {
    ClickStatsQueryService local =
        new ClickStatsQueryService(
            urls,
            stats,
            Clock.fixed(Instant.parse("2026-09-15T04:30:00Z"), ZoneOffset.ofHours(-5)));
    when(urls.existsByShortCode(CODE)).thenReturn(true);
    when(stats.findById(CODE)).thenReturn(Optional.empty());
    when(stats.findDaily(eq(CODE), any(), any())).thenReturn(List.of());

    local.stats(CODE);

    verify(stats).findDaily(CODE, LocalDate.parse("2026-08-17"), LocalDate.parse("2026-09-15"));
  }

  @Test
  void constructorRejectsNullCollaborators() {
    assertThatThrownBy(() -> new ClickStatsQueryService(null, stats))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ClickStatsQueryService(urls, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ClickStatsQueryService(urls, stats, null))
        .isInstanceOf(NullPointerException.class);
  }

  // --- DTO invariants --------------------------------------------------------------------------

  @Test
  void responseFreezesTheDailyListAndRejectsInvalidValues() {
    List<ClickStatsDayEntry> days = new ArrayList<>();
    days.add(new ClickStatsDayEntry(LocalDate.parse("2026-09-01"), 1));
    ClickStatsResponse response = new ClickStatsResponse(1, null, NOW, days);
    days.clear();

    assertThat(response.clicksByDay()).hasSize(1);
    assertThatThrownBy(() -> response.clicksByDay().add(days.isEmpty() ? null : days.get(0)))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> new ClickStatsResponse(-1, null, NOW, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ClickStatsResponse(0, null, null, List.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ClickStatsResponse(0, null, NOW, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void dayEntryRejectsNullDateAndNegativeCount() {
    assertThatThrownBy(() -> new ClickStatsDayEntry(null, 1))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ClickStatsDayEntry(LocalDate.parse("2026-09-01"), -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ClickStatsDaily daily(String day, long count) {
    return new ClickStatsDaily(new ClickStatsDaily.Key(CODE, LocalDate.parse(day)), count);
  }
}
