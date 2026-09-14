/*
 * UrlWriteController.java — HTTP adapter of the write surface: POST /api/v1/urls -> 201 Created
 *
 * Layer: write. Thin @RestController that validates the JSON body (Bean Validation via @Valid on
 * CreateUrlRequest, AC-2/AC-5/AC-6), delegates to UrlWriteService and renders the 201 response
 * with a Location header pointing at the new short URL (AC-1). It carries the springdoc metadata
 * for the createShortUrl operation of openapi.yaml (kept in sync by OpenApiContractIT) and is
 * gated with @Profile("!read") so write instances can be deployed separately from the redirect
 * fleet (AC-14). Non-2xx outcomes (400, 409, 500) are exceptions rendered by
 * GlobalExceptionHandler as problem+json; no error body is built here.
 */
package com.example.shortener.write;

import com.example.shortener.api.dto.CreateUrlRequest;
import com.example.shortener.api.dto.CreateUrlResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/urls} (operationId {@code createShortUrl}): the only write endpoint.
 *
 * <p>Registered under the default and {@code write} profiles and absent under the {@code read}
 * profile, so a read-only instance answers 404 to this route (AC-14). The body is validated with
 * {@code @Valid}; every non-2xx outcome (400 validation / malformed body, 409 taken alias, 500
 * unexpected) is rendered by {@code GlobalExceptionHandler} as {@code application/problem+json}.
 * This class never builds an error body itself.
 *
 * <p>Why the read/write split: creations are rare and need the Redis counter (batches of {@code
 * shortener.counter-batch-size} values reserved per instance), redirects are frequent and need only
 * the cache. Gating this controller on {@code !read} keeps the redirect fleet from ever reserving
 * counter batches, and the negated expression lets the default profile serve both.
 *
 * <p>Validation happens in two layers: structural rules ({@code @NotBlank}, {@code @Size},
 * {@code @Pattern}, {@code @Future}) are declared on {@link CreateUrlRequest} and rejected here
 * before the service runs, so nothing is persisted; semantic rules (a parseable absolute URL with a
 * host, an expiry after the service clock's "now", alias availability) live in {@link
 * UrlWriteService}.
 *
 * <p>Relationships: depends only on {@link UrlWriteService} and the two DTOs. Thread-safety:
 * stateless apart from the injected service; a singleton shared by all requests.
 */
@RestController
@Profile(UrlWriteController.PROFILE_EXPRESSION)
@RequestMapping(path = UrlWriteController.PATH)
@Tag(name = "urls", description = "Short link creation")
public class UrlWriteController {

  /**
   * Profile expression: registered everywhere except on read-only instances. Negated ({@code
   * !read}) rather than positive ({@code write}) so the default profile serves both surfaces.
   */
  public static final String PROFILE_EXPRESSION = "!read";

  /**
   * Route of the write endpoint, declared on the class-level {@code @RequestMapping}; the method
   * mapping adds no path segment. Versioned so a future contract change can coexist with v1.
   */
  public static final String PATH = "/api/v1/urls";

  /** Validates, allocates the code, persists the row and renders the response body. */
  private final UrlWriteService service;

  /**
   * Creates the controller.
   *
   * @param service the write service that creates mappings
   * @throws NullPointerException when {@code service} is {@code null}
   */
  public UrlWriteController(UrlWriteService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  /**
   * Creates a short link for {@code long_url}, optionally with a custom alias and an expiry.
   *
   * <p>By the time this method runs the body has been parsed (a malformed or non-JSON body is a 400
   * {@code malformed-request}, an unsupported {@code Content-Type} a 415) and its Bean Validation
   * constraints hold (otherwise a 400 {@code validation-error}), so the service is only reached
   * with structurally valid input. The {@code Location} header of the 201 is the new resource, that
   * is the short URL, not the long URL. Failures are not handled here: {@code InvalidUrlException}
   * becomes 400, {@code AliasAlreadyExistsException} 409, anything else 500.
   *
   * @param request the validated request body
   * @return 201 Created with the mapping and a {@code Location} header pointing at the short URL
   */
  @Operation(
      operationId = "createShortUrl",
      summary = "Create a short URL",
      description =
          "Shortens an absolute http/https URL. Uses custom_alias as the short code when supplied,"
              + " otherwise allocates a base62 code. Identical long_urls are never deduplicated.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "Short URL created",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = CreateUrlResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description =
            "Invalid long_url, custom_alias or expiration_date, or unreadable JSON body;"
                + " nothing persisted",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "409",
        description = "custom_alias / short_code already taken; existing mapping unchanged",
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
  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<CreateUrlResponse> createShortUrl(
      @Valid @RequestBody CreateUrlRequest request) {
    CreateUrlResponse response = service.create(request);
    // ResponseEntity.created(...) sets status 201 and Location; the short URL is built by the
    // service from shortener.base-url, so it is a well-formed absolute URI here.
    return ResponseEntity.created(URI.create(response.shortUrl())).body(response);
  }
}
