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
 * @param longUrl absolute {@code http} or {@code https} URL, non-blank, at most 2048 characters
 * @param customAlias optional caller-chosen short code, {@code [0-9a-zA-Z]} and 3 to 32 characters
 * @param expirationDate optional ISO-8601 date-time (with offset) that must lie in the future; a
 *     value that cannot be parsed as ISO-8601 is rejected while reading the body
 */
public record CreateUrlRequest(
    @JsonProperty("long_url")
        @NotBlank(message = "must not be blank")
        @Size(max = MAX_LONG_URL_LENGTH, message = "must be at most 2048 characters")
        @Pattern(regexp = LONG_URL_PATTERN, message = "must be an absolute http or https URL")
        String longUrl,
    @JsonProperty("custom_alias")
        @Pattern(
            regexp = CUSTOM_ALIAS_PATTERN,
            message = "must be 3 to 32 characters from [0-9a-zA-Z]")
        String customAlias,
    @JsonProperty("expiration_date") @Future(message = "must be in the future")
        OffsetDateTime expirationDate) {

  /** Maximum accepted length of {@code long_url}. */
  public static final int MAX_LONG_URL_LENGTH = 2048;

  /** {@code long_url} must be an absolute http/https URL. */
  public static final String LONG_URL_PATTERN = "^https?://.+";

  /** {@code custom_alias} charset and length. */
  public static final String CUSTOM_ALIAS_PATTERN = "^[0-9a-zA-Z]{3,32}$";
}
