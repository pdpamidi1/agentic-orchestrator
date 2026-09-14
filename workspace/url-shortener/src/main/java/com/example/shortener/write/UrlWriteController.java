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
 */
@RestController
@Profile(UrlWriteController.PROFILE_EXPRESSION)
@RequestMapping(path = UrlWriteController.PATH)
@Tag(name = "urls", description = "Short link creation")
public class UrlWriteController {

  /** Profile expression: registered everywhere except on read-only instances. */
  public static final String PROFILE_EXPRESSION = "!read";

  /** Route of the write endpoint. */
  public static final String PATH = "/api/v1/urls";

  private final UrlWriteService service;

  public UrlWriteController(UrlWriteService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  /**
   * Creates a short link for {@code long_url}, optionally with a custom alias and an expiry.
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
    return ResponseEntity.created(URI.create(response.shortUrl())).body(response);
  }
}
