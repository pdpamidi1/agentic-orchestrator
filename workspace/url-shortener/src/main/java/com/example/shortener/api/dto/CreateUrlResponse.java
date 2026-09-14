package com.example.shortener.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Response body of {@code POST /api/v1/urls} (201 Created), serialised in snake_case.
 *
 * <p>This DTO depends on nothing inside the application: {@code code_source} carries the wire value
 * of the domain enum ({@code "redis"} or {@code "db_sequence"}) as a plain string.
 *
 * @param shortUrl configured base URL plus the short code
 * @param shortCode the generated base62 code or the custom alias
 * @param longUrl the stored target URL
 * @param expiresAt expiry instant, or {@code null} when the link never expires
 * @param codeSource which counter produced the code: {@code "redis"} or {@code "db_sequence"}
 */
public record CreateUrlResponse(
    @JsonProperty("short_url") String shortUrl,
    @JsonProperty("short_code") String shortCode,
    @JsonProperty("long_url") String longUrl,
    @JsonProperty("expires_at") Instant expiresAt,
    @JsonProperty("code_source") String codeSource) {}
