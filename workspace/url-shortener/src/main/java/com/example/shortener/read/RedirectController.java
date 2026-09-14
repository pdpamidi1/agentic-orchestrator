package com.example.shortener.read;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /{short_code}} (operationId {@code redirectToLongUrl}): the only read endpoint.
 *
 * <p>Registered under the default and {@code read} profiles and absent under the {@code write}
 * profile, so a write-only instance answers 404 to this route (AC-14). A resolved code yields
 * {@code 302 Found} with {@code Location} set to the stored {@code long_url} verbatim and {@code
 * Cache-Control: private} (AC-7). Every non-2xx outcome (404 unknown, 410 expired, 500 unexpected)
 * is rendered by {@code GlobalExceptionHandler} as {@code application/problem+json}; this class
 * never builds an error body and never catches infrastructure exceptions.
 */
@RestController
@Profile(RedirectController.PROFILE_EXPRESSION)
@Tag(name = "redirect", description = "Short link resolution")
public class RedirectController {

  /** Profile expression: registered everywhere except on write-only instances. */
  public static final String PROFILE_EXPRESSION = "!write";

  /** Route of the redirect endpoint. */
  public static final String PATH = "/{short_code}";

  /** Value of the {@code Cache-Control} header on every redirect. */
  static final CacheControl CACHE_CONTROL = CacheControl.empty().cachePrivate();

  private final UrlReadService service;

  public RedirectController(UrlReadService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  /**
   * Redirects to the long URL behind a short code.
   *
   * @param shortCode the short code from the path
   * @return 302 Found with {@code Location} = long URL and {@code Cache-Control: private}
   */
  @Operation(
      operationId = "redirectToLongUrl",
      summary = "Redirect to the long URL",
      description =
          "Resolves the short code (cache first, then database) and answers with a 302 redirect"
              + " to the stored long_url. Expired links answer 410, unknown links 404.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "302",
        description = "Redirect to the stored long_url",
        headers = {
          @Header(
              name = HttpHeaders.LOCATION,
              description = "The stored long_url",
              schema = @Schema(type = "string", format = "uri")),
          @Header(
              name = HttpHeaders.CACHE_CONTROL,
              description = "Always `private`",
              schema = @Schema(type = "string"))
        }),
    @ApiResponse(
        responseCode = "404",
        description = "Short code unknown in cache and database",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "410",
        description = "Short code expired (expires_at in the past)",
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
  @GetMapping(path = PATH)
  public ResponseEntity<Void> redirectToLongUrl(
      @Parameter(
              name = "short_code",
              description = "Generated base62 code or custom alias",
              example = "promo2024")
          @PathVariable("short_code")
          String shortCode) {
    String longUrl = service.resolve(shortCode);
    // The stored long_url is forwarded verbatim; no re-parsing that could alter or reject it.
    return ResponseEntity.status(HttpStatus.FOUND)
        .header(HttpHeaders.LOCATION, longUrl)
        .cacheControl(CACHE_CONTROL)
        .build();
  }
}
