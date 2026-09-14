/*
 * ClickStatsController.java — HTTP adapter of the analytics read surface:
 * GET /api/v1/urls/{short_code}/stats -> 200 ClickStatsResponse
 *
 * Layer: analytics.api. Thin @RestController that delegates to ClickStatsQueryService and renders
 * the JSON body. It carries the springdoc metadata for the getUrlClickStats operation of
 * openapi.yaml (kept in sync by OpenApiContractIT) and, like the write surface, is gated with
 * @Profile("!read") so the redirect fleet never serves the management API. Non-2xx outcomes (404,
 * 500) are exceptions rendered by GlobalExceptionHandler as problem+json; no error body is built
 * here (AC-8, AC-9, AC-14, AC-15).
 */
package com.example.shortener.analytics.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/urls/{short_code}/stats} (operationId {@code getUrlClickStats}): aggregated
 * click statistics of one short link.
 *
 * <p>Additive to the existing API: no existing operation, field or status code changes. Registered
 * under the default and {@code write} profiles and absent under the {@code read} profile (same gate
 * as {@code UrlWriteController}), so a read-only redirect instance answers 404 to this route. Every
 * non-2xx outcome (404 unknown short code, 500 unexpected) is rendered by {@code
 * GlobalExceptionHandler} as {@code application/problem+json}; this class never builds an error
 * body itself.
 *
 * <p>Relationships: depends only on {@link ClickStatsQueryService} and the two DTOs. Thread-safety:
 * stateless apart from the injected service; a singleton shared by all requests.
 */
@RestController
@Profile(ClickStatsController.PROFILE_EXPRESSION)
@RequestMapping(path = ClickStatsController.PATH)
@Tag(name = "analytics", description = "Click statistics of short links")
public class ClickStatsController {

  /** Profile expression: registered everywhere except on read-only redirect instances. */
  public static final String PROFILE_EXPRESSION = "!read";

  /** Route template, declared on the class-level {@code @RequestMapping}. */
  public static final String PATH = "/api/v1/urls/{short_code}/stats";

  /** Assembles the response from the aggregates. */
  private final ClickStatsQueryService service;

  /**
   * Creates the controller.
   *
   * @param service the query service
   * @throws NullPointerException when {@code service} is {@code null}
   */
  public ClickStatsController(ClickStatsQueryService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  /**
   * Returns the aggregated click statistics of a short code.
   *
   * @param shortCode the short code from the path
   * @return 200 with total, last click, freshness and the sparse 30-day daily breakdown
   */
  @Operation(
      operationId = "getUrlClickStats",
      summary = "Aggregated click statistics of a short URL",
      description =
          "Returns total_clicks, last_clicked_at, as_of (freshness of the aggregate) and a sparse"
              + " ascending clicks_by_day over the last 30 UTC days (days with at least one click"
              + " only). A link that was never clicked answers 0 / null / [].")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Aggregated click statistics",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ClickStatsResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "Short code unknown",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "500",
        description = "Unhandled server error",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class)))
  })
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public ClickStatsResponse getUrlClickStats(
      @Parameter(description = "Generated base62 code or custom alias", example = "promo2024")
          @PathVariable("short_code")
          String shortCode) {
    return service.stats(shortCode);
  }
}
