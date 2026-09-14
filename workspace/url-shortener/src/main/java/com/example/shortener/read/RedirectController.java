/*
 * RedirectController.java — HTTP adapter of the read surface: GET /{short_code} -> 302 Found
 *
 * Layer: read. Thin @RestController that hands the path variable to UrlReadService and turns the
 * resolved long URL into a 302 with Location and Cache-Control: private (AC-7). It carries the
 * springdoc annotations that generate the redirectToLongUrl operation of openapi.yaml (kept in
 * sync by OpenApiContractIT) and is gated with @Profile("!write") so the read surface can be
 * deployed and scaled independently of the write surface (AC-14). Non-2xx outcomes (404 unknown,
 * 410 expired, 500 unexpected) are exceptions rendered by GlobalExceptionHandler as problem+json;
 * no error body is built here.
 */
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
 *
 * <p>Why the read/write split: redirects are the high-volume, latency-sensitive path and creations
 * are rare, so the two surfaces are separately registrable beans selected by {@code
 * SPRING_PROFILES_ACTIVE}. A fleet of {@code read} replicas behind the public hostname never
 * reserves counter batches from Redis; a few {@code write} replicas serve the API path. Both share
 * the same Postgres and Redis, so there is no replication lag between them.
 *
 * <p>Why 302 and not 301: a permanent redirect would let browsers cache the target forever, which
 * would hide a later expiry (410). Combined with {@code Cache-Control: private}, shared caches and
 * CDNs never store the redirect, so every client eventually observes the database state.
 *
 * <p>Relationships: depends only on {@link UrlReadService}; the OpenAPI metadata declared here is
 * what {@code OpenApiContractIT} compares against {@code src/main/resources/openapi.yaml}.
 * Thread-safety: stateless apart from the injected service; a singleton shared by all requests.
 */
@RestController
@Profile(RedirectController.PROFILE_EXPRESSION)
@Tag(name = "redirect", description = "Short link resolution")
public class RedirectController {

  /**
   * Profile expression: registered everywhere except on write-only instances. Negated ({@code
   * !write}) rather than positive ({@code read}) so the default profile serves both surfaces.
   */
  public static final String PROFILE_EXPRESSION = "!write";

  /**
   * Route of the redirect endpoint. The path variable name {@code short_code} matches the OpenAPI
   * parameter name in {@code openapi.yaml}.
   */
  public static final String PATH = "/{short_code}";

  /**
   * Value of the {@code Cache-Control} header on every redirect. {@code CacheControl.empty()
   * .cachePrivate()} renders exactly {@code private}: end-user browsers may cache the response,
   * shared caches (proxies, CDNs) may not, so expiry and 410 handling are not masked upstream.
   */
  static final CacheControl CACHE_CONTROL = CacheControl.empty().cachePrivate();

  /** Resolves short codes (cache first, then Postgres); the controller's only collaborator. */
  private final UrlReadService service;

  /**
   * Creates the controller.
   *
   * @param service the read service resolving short codes to long URLs
   * @throws NullPointerException when {@code service} is {@code null}
   */
  public RedirectController(UrlReadService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  /**
   * Redirects to the long URL behind a short code.
   *
   * <p>Delegates to {@link UrlReadService#resolve}. The stored {@code long_url} is placed in {@code
   * Location} as-is: it was validated as an absolute http/https URL when it was created, and
   * re-parsing it here could alter percent-encoding or reject a URL that is legitimately stored.
   * The response has no body. Failures are not handled here: {@code ShortCodeNotFoundException}
   * becomes 404 and {@code ShortCodeExpiredException} becomes 410 in {@code
   * GlobalExceptionHandler}; any other exception becomes a 500 without internal details.
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
    // ResponseEntity.location(URI) is avoided for the same reason: it would require building a
    // URI object from the string.
    return ResponseEntity.status(HttpStatus.FOUND)
        .header(HttpHeaders.LOCATION, longUrl)
        .cacheControl(CACHE_CONTROL)
        .build();
  }
}
