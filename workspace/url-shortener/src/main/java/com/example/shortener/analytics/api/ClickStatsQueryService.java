/*
 * ClickStatsQueryService.java — Read model behind GET /api/v1/urls/{short_code}/stats
 *
 * Layer: analytics.api (service). Reads the aggregates maintained by the Kafka consumer
 * (click_stats, click_stats_daily via ClickStatsRepository) and checks link existence through
 * UrlMappingRepository; it never touches the outbox, the consumer, Kafka or the retention job
 * (architecture rule: the stats endpoint reads aggregates only). Unknown short codes raise the
 * existing ShortCodeNotFoundException, which GlobalExceptionHandler renders as 404 problem+json.
 * Serves AC-8, AC-9 and AC-14.
 */
package com.example.shortener.analytics.api;

import com.example.shortener.analytics.domain.ClickStats;
import com.example.shortener.analytics.domain.ClickStatsDaily;
import com.example.shortener.analytics.repository.ClickStatsRepository;
import com.example.shortener.api.error.ShortCodeNotFoundException;
import com.example.shortener.domain.UrlMappingRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the {@link ClickStatsResponse} of one short code from the analytics aggregates.
 *
 * <p><b>Algorithm.</b> (1) the link must exist in {@code urls}, otherwise {@link
 * ShortCodeNotFoundException} (404); (2) {@code click_stats} is read by primary key: when present
 * it supplies {@code total_clicks}, {@code last_clicked_at} and {@code as_of = updated_at}, when
 * absent the link has never been clicked and the response is {@code 0 / null / now}; (3) {@code
 * click_stats_daily} is read for the inclusive window {@code [today - 29, today]} in UTC ({@link
 * #WINDOW_DAYS} days ending with the current UTC day of the injected clock), rows with a zero count
 * are dropped, and the rest become the ascending sparse {@code clicks_by_day}.
 *
 * <p><b>Consistency.</b> The two aggregate reads run in one read-only transaction, so the total and
 * the daily breakdown come from one snapshot. Because the consumer updates the aggregates
 * asynchronously, the numbers can lag behind the redirects by the outbox relay and consumer
 * latency; {@code as_of} tells the client how fresh they are.
 *
 * <p><b>Thread-safety and lifecycle.</b> Stateless singleton Spring bean apart from the injected
 * collaborators; safe for concurrent use.
 */
@Service
public class ClickStatsQueryService {

  /** Length of the daily window in UTC days, including the current day (AC-8). */
  public static final int WINDOW_DAYS = 30;

  /** System of record for links; decides 404. */
  private final UrlMappingRepository urls;

  /** Aggregates maintained by the consumer. */
  private final ClickStatsRepository stats;

  /** Source of "now": window end and {@code as_of} fallback. Fixed in tests, UTC system live. */
  private final Clock clock;

  /**
   * Creates the service with the system UTC clock.
   *
   * <p>This is the constructor Spring uses ({@link Autowired}); it delegates to the explicit one.
   *
   * @param urls the {@code urls} repository
   * @param stats the aggregates repository
   * @throws NullPointerException when either collaborator is {@code null}
   */
  @Autowired
  public ClickStatsQueryService(UrlMappingRepository urls, ClickStatsRepository stats) {
    this(urls, stats, Clock.systemUTC());
  }

  /**
   * Creates the service with an explicit clock.
   *
   * @param urls the {@code urls} repository
   * @param stats the aggregates repository
   * @param clock source of the "now" that ends the 30-day window and stands in for {@code as_of}
   * @throws NullPointerException when any argument is {@code null}
   */
  public ClickStatsQueryService(
      UrlMappingRepository urls, ClickStatsRepository stats, Clock clock) {
    this.urls = Objects.requireNonNull(urls, "urls");
    this.stats = Objects.requireNonNull(stats, "stats");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Builds the aggregated statistics of a short code.
   *
   * @param shortCode the short code from the request path
   * @return the response body; {@code total_clicks = 0}, {@code last_clicked_at = null} and an
   *     empty {@code clicks_by_day} for a link that was never clicked
   * @throws ShortCodeNotFoundException when no link has that short code (404)
   * @throws NullPointerException when {@code shortCode} is {@code null}
   */
  @Transactional(readOnly = true)
  public ClickStatsResponse stats(String shortCode) {
    Objects.requireNonNull(shortCode, "shortCode");
    if (!urls.existsByShortCode(shortCode)) {
      throw new ShortCodeNotFoundException(shortCode);
    }
    Instant now = clock.instant();
    Optional<ClickStats> total = stats.findById(shortCode);

    LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
    LocalDate from = today.minusDays(WINDOW_DAYS - 1L);
    List<ClickStatsDayEntry> byDay =
        stats.findDaily(shortCode, from, today).stream()
            .filter(day -> day.getCount() >= 1)
            .map(ClickStatsQueryService::entry)
            .toList();

    return new ClickStatsResponse(
        total.map(ClickStats::getTotalClicks).orElse(0L),
        total.map(ClickStats::getLastClickedAt).orElse(null),
        total.map(ClickStats::getUpdatedAt).orElse(now),
        byDay);
  }

  /**
   * Maps a daily aggregate row to its response element.
   *
   * @param day the row
   * @return the entry with the row's day and count
   */
  private static ClickStatsDayEntry entry(ClickStatsDaily day) {
    return new ClickStatsDayEntry(day.getDay(), day.getCount());
  }
}
