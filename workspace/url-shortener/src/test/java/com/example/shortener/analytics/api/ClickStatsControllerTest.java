/*
 * ClickStatsControllerTest.java — MockMvc tests for GET /api/v1/urls/{short_code}/stats
 *
 * Layer: test. Pins the HTTP contract of ClickStatsController with a standalone MockMvcTester (no
 * Spring context) over the real ClickStatsQueryService, Mockito mocks of the two repositories, a
 * fixed clock and the real GlobalExceptionHandler: 200 with data (AC-8), 200 for a never-clicked
 * link (AC-9), 404 problem+json for an unknown code (AC-14), the 30-day UTC window boundary
 * (AC-8), 500 without internal details, and the @Profile("!read") gating (AC-15 additive surface).
 * There is no /api/v1 authentication scheme in this code base (no security dependency), so no
 * unauthenticated case exists to pin.
 */
package com.example.shortener.analytics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.shortener.analytics.domain.ClickStats;
import com.example.shortener.analytics.domain.ClickStatsDaily;
import com.example.shortener.analytics.repository.ClickStatsRepository;
import com.example.shortener.api.error.GlobalExceptionHandler;
import com.example.shortener.domain.UrlMappingRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@link ClickStatsController} behind a standalone MockMvc with the real {@link
 * ClickStatsQueryService} (mocked repositories, fixed clock) and the real {@link
 * GlobalExceptionHandler}, so both the JSON rendering and the problem+json errors are exercised end
 * to end without a Spring context.
 */
class ClickStatsControllerTest {

  /** Short code used by every request. */
  private static final String CODE = "promo2024";

  /** Fixed "now": 2026-09-14T10:00Z, so the window is 2026-08-16 .. 2026-09-14 inclusive. */
  private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");

  /** Contract values of {@link GlobalExceptionHandler} (package-private there). */
  private static final String PROBLEM_TYPE_BASE = "https://example.com/problems/";

  /** The fixed, non-revealing {@code detail} every 500 carries. */
  private static final String DETAIL_INTERNAL_ERROR =
      "An unexpected error occurred while processing the request";

  private final UrlMappingRepository urls = mock(UrlMappingRepository.class);
  private final ClickStatsRepository stats = mock(ClickStatsRepository.class);

  private final MockMvcTester mvc = mvcAt(Clock.fixed(NOW, ZoneOffset.UTC));

  // --- 200 with data (AC-8) --------------------------------------------------------------------

  /**
   * An existing link with an aggregate row and two daily rows answers 200 with the four snake_case
   * fields, {@code as_of} equal to {@code click_stats.updated_at} and {@code clicks_by_day} in
   * ascending date order with {@code date} / {@code count} entries.
   */
  @Test
  void returnsAggregatedStatsWithSparseAscendingDailyBreakdown() {
    Instant last = Instant.parse("2026-09-13T08:30:00Z");
    Instant updated = Instant.parse("2026-09-13T08:30:05Z");
    when(urls.existsByShortCode(CODE)).thenReturn(true);
    when(stats.findById(CODE)).thenReturn(Optional.of(new ClickStats(CODE, 42, last, updated)));
    when(stats.findDaily(eq(CODE), any(), any()))
        .thenReturn(
            List.of(
                daily(LocalDate.parse("2026-09-01"), 40), daily(LocalDate.parse("2026-09-13"), 2)));

    MvcTestResult result = get(CODE);

    assertThat(result)
        .hasStatus(HttpStatus.OK)
        .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
    assertThat(result).bodyJson().extractingPath("$.total_clicks").isEqualTo(42);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.last_clicked_at")
        .asString()
        .startsWith("2026-09-13T08:30:00");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.as_of")
        .asString()
        .startsWith("2026-09-13T08:30:05");
    assertThat(result).bodyJson().extractingPath("$.clicks_by_day[0].date").isEqualTo("2026-09-01");
    assertThat(result).bodyJson().extractingPath("$.clicks_by_day[0].count").isEqualTo(40);
    assertThat(result).bodyJson().extractingPath("$.clicks_by_day[1].date").isEqualTo("2026-09-13");
    assertThat(result).bodyJson().extractingPath("$.clicks_by_day[1].count").isEqualTo(2);
    assertThat(result).bodyJson().doesNotHavePath("$.clicks_by_day[2]");
    assertThat(result).bodyJson().doesNotHavePath("$.totalClicks");
    assertThat(result).bodyJson().doesNotHavePath("$.clicksByDay");
  }

  // --- 200 empty (AC-9) ------------------------------------------------------------------------

  /**
   * An existing link with no aggregate row answers 200 with {@code total_clicks = 0}, {@code
   * last_clicked_at = null}, {@code as_of = now} and an empty {@code clicks_by_day}.
   */
  @Test
  void neverClickedLinkReturnsZeroNullAndEmptyList() {
    when(urls.existsByShortCode(CODE)).thenReturn(true);
    when(stats.findById(CODE)).thenReturn(Optional.empty());
    when(stats.findDaily(eq(CODE), any(), any())).thenReturn(List.of());

    MvcTestResult result = get(CODE);

    assertThat(result).hasStatus(HttpStatus.OK);
    assertThat(result).bodyJson().extractingPath("$.total_clicks").isEqualTo(0);
    assertThat(result).bodyJson().extractingPath("$.last_clicked_at").isNull();
    assertThat(result)
        .bodyJson()
        .extractingPath("$.as_of")
        .asString()
        .startsWith("2026-09-14T10:00:00");
    assertThat(result).bodyJson().extractingPath("$.clicks_by_day").asArray().isEmpty();
  }

  // --- 404 (AC-14) -----------------------------------------------------------------------------

  /**
   * An unknown short code is a 404 {@code short-code-not-found} problem+json through the existing
   * handler; the aggregates are never read.
   */
  @Test
  void unknownShortCodeIs404ProblemAndAggregatesAreNotRead() {
    when(urls.existsByShortCode("nope")).thenReturn(false);

    MvcTestResult result = get("nope");

    assertProblem(result, HttpStatus.NOT_FOUND, "short-code-not-found", "/api/v1/urls/nope/stats");
    assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Short code not found");
    assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("nope");
    verifyNoInteractions(stats);
  }

  // --- 30-day window boundary (AC-8) -----------------------------------------------------------

  /**
   * The daily query covers exactly the last 30 UTC days including today ({@code today - 29 ..
   * today}), and zero-count rows inside the window are dropped from the sparse list.
   */
  @Test
  void dailyWindowIsLast30UtcDaysInclusiveAndZeroDaysAreDropped() {
    when(urls.existsByShortCode(CODE)).thenReturn(true);
    when(stats.findById(CODE)).thenReturn(Optional.empty());
    when(stats.findDaily(eq(CODE), any(), any()))
        .thenReturn(
            List.of(
                daily(LocalDate.parse("2026-08-16"), 1),
                daily(LocalDate.parse("2026-08-20"), 0),
                daily(LocalDate.parse("2026-09-14"), 3)));

    MvcTestResult result = get(CODE);

    verify(stats).findDaily(CODE, LocalDate.parse("2026-08-16"), LocalDate.parse("2026-09-14"));
    assertThat(result).hasStatus(HttpStatus.OK);
    assertThat(result).bodyJson().extractingPath("$.clicks_by_day[0].date").isEqualTo("2026-08-16");
    assertThat(result).bodyJson().extractingPath("$.clicks_by_day[1].date").isEqualTo("2026-09-14");
    assertThat(result).bodyJson().doesNotHavePath("$.clicks_by_day[2]");
  }

  /**
   * The window is computed on the UTC calendar day, not the server's zone: at 2026-09-14T23:30 in
   * UTC-5 it is already 2026-09-15 in UTC, so the window ends on the 15th and starts on 08-17.
   */
  @Test
  void dailyWindowUsesTheUtcCalendarDay() {
    MockMvcTester localMvc =
        mvcAt(Clock.fixed(Instant.parse("2026-09-15T04:30:00Z"), ZoneOffset.ofHours(-5)));
    when(urls.existsByShortCode(CODE)).thenReturn(true);
    when(stats.findById(CODE)).thenReturn(Optional.empty());
    when(stats.findDaily(anyString(), any(), any())).thenReturn(List.of());

    MvcTestResult result = localMvc.get().uri(PATH_TEMPLATE, CODE).exchange();

    assertThat(result).hasStatus(HttpStatus.OK);
    verify(stats).findDaily(CODE, LocalDate.parse("2026-08-17"), LocalDate.parse("2026-09-15"));
  }

  // --- 500 -------------------------------------------------------------------------------------

  /** A repository failure becomes a 500 problem+json with the fixed detail and no internals. */
  @Test
  void unexpectedFailureIs500ProblemWithoutInternalDetails() {
    when(urls.existsByShortCode(CODE)).thenThrow(new IllegalStateException("pool exhausted"));

    MvcTestResult result = get(CODE);

    assertProblem(
        result, HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "/api/v1/urls/promo2024/stats");
    assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo(DETAIL_INTERNAL_ERROR);
    assertThat(bodyOf(result)).doesNotContain("pool exhausted").doesNotContain("com.example");
  }

  // --- profile gating --------------------------------------------------------------------------

  /** The class is a {@code @RestController} gated on {@code !read}, mapped to the stats route. */
  @Test
  void controllerIsGatedOnNotReadProfileAndMapsGetStatsRoute() throws NoSuchMethodException {
    Profile profile = ClickStatsController.class.getAnnotation(Profile.class);
    assertThat(profile).isNotNull();
    assertThat(profile.value()).containsExactly("!read");
    assertThat(ClickStatsController.class.isAnnotationPresent(RestController.class)).isTrue();
    assertThat(ClickStatsController.class.getAnnotation(RequestMapping.class).path())
        .containsExactly("/api/v1/urls/{short_code}/stats");
    GetMapping mapping =
        ClickStatsController.class
            .getMethod("getUrlClickStats", String.class)
            .getAnnotation(GetMapping.class);
    assertThat(mapping).isNotNull();
    assertThat(mapping.path()).isEmpty();
  }

  /** The bean is absent under {@code read} and present under {@code write} and no profile. */
  @Test
  void controllerIsAbsentUnderTheReadProfileAndPresentOtherwise() {
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withBean(ClickStatsQueryService.class, () -> mock(ClickStatsQueryService.class))
            .withUserConfiguration(ClickStatsController.class);

    runner
        .withPropertyValues("spring.profiles.active=read")
        .run(context -> assertThat(context).doesNotHaveBean(ClickStatsController.class));
    runner
        .withPropertyValues("spring.profiles.active=write")
        .run(context -> assertThat(context).hasSingleBean(ClickStatsController.class));
    runner.run(context -> assertThat(context).hasSingleBean(ClickStatsController.class));
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static final String PATH_TEMPLATE = "/api/v1/urls/{short_code}/stats";

  private MockMvcTester mvcAt(Clock clock) {
    ClickStatsController controller =
        new ClickStatsController(new ClickStatsQueryService(urls, stats, clock));
    return MockMvcTester.of(
        List.of(controller),
        builder -> builder.setControllerAdvice(new GlobalExceptionHandler()).build());
  }

  private MvcTestResult get(String code) {
    return mvc.get().uri(PATH_TEMPLATE, code).exchange();
  }

  private static ClickStatsDaily daily(LocalDate day, long count) {
    return new ClickStatsDaily(new ClickStatsDaily.Key(CODE, day), count);
  }

  private static void assertProblem(
      MvcTestResult result, HttpStatus status, String typeSlug, String instance) {
    assertThat(result).hasStatus(status).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(status.value());
    assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(PROBLEM_TYPE_BASE + typeSlug);
    assertThat(result).bodyJson().extractingPath("$.title").asString().isNotBlank();
    assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo(instance);
    assertThat(result).bodyJson().doesNotHavePath("$.stackTrace");
    assertThat(result).bodyJson().doesNotHavePath("$.trace");
  }

  private static String bodyOf(MvcTestResult result) {
    try {
      return result.getResponse().getContentAsString();
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }
}
