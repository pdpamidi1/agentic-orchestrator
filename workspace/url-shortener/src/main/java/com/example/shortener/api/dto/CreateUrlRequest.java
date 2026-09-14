/*
 * CreateUrlRequest.java — Request body record of POST /api/v1/urls (createShortUrl)
 *
 * Layer: api. Defines the inbound JSON shape of the write surface together with the Bean
 * Validation rules that turn structurally invalid input into a 400 before any service code runs.
 * Consumed by UrlWriteController (@Valid @RequestBody) and UrlWriteService (semantic re-checks);
 * its constants are reused by UrlWriteService for the alias pattern and the length limit. Mirrors
 * the CreateUrlRequest schema of openapi.yaml (AC-2, AC-5, AC-6).
 */
package com.example.shortener.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/**
 * Request body of {@code POST /api/v1/urls} (operationId {@code createShortUrl}).
 *
 * <p>JSON property names are snake_case as mandated by the API contract. Structural rules are
 * expressed as Bean Validation constraints so that a controller's {@code @Valid} rejects invalid
 * input with a 400 before any service code runs (AC-2, AC-5, AC-6).
 *
 * <p><b>Responsibility.</b> Carry the three client-supplied fields and their syntactic rules.
 * Semantic rules that Bean Validation cannot express (a parsable URL with a host, an expiry that is
 * still in the future relative to the service clock) are re-checked by {@code UrlWriteService},
 * which raises {@code InvalidUrlException} for them; the constraints here are the first line of
 * defence and the source of the {@code validation-error} problem details.
 *
 * <p><b>Invariants.</b> None are enforced by the record itself: a {@code CreateUrlRequest} may be
 * constructed with any values (tests do so). Only a validated instance, i.e. one that passed
 * {@code @Valid}, satisfies the rules listed on the components. {@code customAlias} and {@code
 * expirationDate} are optional and {@code null} when absent from the body.
 *
 * <p><b>Thread-safety and lifecycle.</b> Immutable record, created per request by Jackson; not a
 * Spring bean.
 *
 * <p><b>Design choice.</b> A record rather than a mutable class keeps the DTO free of behaviour and
 * makes the OpenAPI schema derivable from the declaration alone; {@code OpenApiContractIT} compares
 * the generated schema against the committed {@code openapi.yaml}.
 *
 * @param longUrl absolute {@code http} or {@code https} URL, non-blank, at most 2048 characters
 * @param customAlias optional caller-chosen short code, {@code [0-9a-zA-Z]} and 3 to 32 characters
 * @param expirationDate optional ISO-8601 date-time (with offset) that must lie in the future; a
 *     value that cannot be parsed as ISO-8601 is rejected while reading the body
 */
public record CreateUrlRequest(
    // Required. @NotBlank rejects null/whitespace, @Size caps the length (also the column width
    // of urls.long_url), @Pattern enforces the scheme prefix; the full URI parse happens later.
    @JsonProperty("long_url")
        @NotBlank(message = "must not be blank")
        @Size(max = MAX_LONG_URL_LENGTH, message = "must be at most 2048 characters")
        @Pattern(regexp = LONG_URL_PATTERN, message = "must be an absolute http or https URL")
        String longUrl,
    // Optional. @Pattern is skipped for null, so an absent alias is valid; when present it must
    // already satisfy the urls.short_code format check constraint of the V1 migration.
    @JsonProperty("custom_alias")
        @Pattern(
            regexp = CUSTOM_ALIAS_PATTERN,
            message = "must be 3 to 32 characters from [0-9a-zA-Z]")
        String customAlias,
    // Optional. @Future is skipped for null; it is evaluated against the validator's clock, the
    // service re-checks against its own injected Clock before persisting.
    @JsonProperty("expiration_date") @Future(message = "must be in the future")
        OffsetDateTime expirationDate) {

  /**
   * Maximum accepted length of {@code long_url}, in characters. Matches the {@code varchar(2048)}
   * width of {@code urls.long_url} and the {@code maxLength} in {@code openapi.yaml}, so a value
   * that passes validation can always be persisted.
   */
  public static final int MAX_LONG_URL_LENGTH = 2048;

  /**
   * {@code long_url} must be an absolute http/https URL. Deliberately loose (only the scheme prefix
   * is checked, case-sensitively) so that the precise, host-aware check can live in one place,
   * {@code UrlWriteService}; the regex is also what {@code openapi.yaml} advertises.
   */
  public static final String LONG_URL_PATTERN = "^https?://.+";

  /**
   * {@code custom_alias} charset and length: 3 to 32 characters from the base62 alphabet. The
   * charset equals the alphabet of generated codes so aliases and generated codes share one key
   * space and one database check constraint ({@code urls_short_code_format_chk}); the upper bound
   * equals the {@code varchar(32)} width of {@code urls.short_code}.
   */
  public static final String CUSTOM_ALIAS_PATTERN = "^[0-9a-zA-Z]{3,32}$";
}
