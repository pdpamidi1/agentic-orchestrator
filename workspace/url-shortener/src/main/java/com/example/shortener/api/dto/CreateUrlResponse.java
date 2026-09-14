/*
 * CreateUrlResponse.java — 201 Created response body of POST /api/v1/urls
 *
 * Layer: api. Defines the outbound JSON of the write surface exactly as the CreateUrlResponse
 * schema in openapi.yaml describes it. Built by UrlWriteService after the mapping is persisted and
 * returned unchanged by UrlWriteController. Deliberately free of domain types so the api.dto
 * package stays a leaf (see ArchitectureTest) and the wire format cannot change by accident when
 * an enum is renamed (AC-1, "code_source semantics" in the README).
 */
package com.example.shortener.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Response body of {@code POST /api/v1/urls} (201 Created), serialised in snake_case.
 *
 * <p>This DTO depends on nothing inside the application: {@code code_source} carries the wire value
 * of the domain enum ({@code "redis"} or {@code "db_sequence"}) as a plain string.
 *
 * <p><b>Responsibility.</b> Describe the mapping that was just created: where it is reachable
 * ({@code short_url}), its key ({@code short_code}), what it points at ({@code long_url}), when it
 * stops working ({@code expires_at}) and which counter produced the code ({@code code_source}).
 *
 * <p><b>Invariants.</b> None enforced here; the record is a plain carrier. By construction in
 * {@code UrlWriteService}, {@code shortUrl} is {@code <shortener.base-url>/<shortCode>}, {@code
 * expiresAt} is {@code null} exactly when the link never expires, and {@code codeSource} is one of
 * the two wire values persisted in {@code urls.code_source}. A custom alias is echoed verbatim as
 * {@code shortCode}.
 *
 * <p><b>Thread-safety and lifecycle.</b> Immutable record created per request; not a Spring bean.
 * {@code expiresAt} is serialised by Jackson as an ISO-8601 instant in UTC.
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
