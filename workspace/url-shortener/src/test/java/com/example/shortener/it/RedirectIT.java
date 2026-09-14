/*
 * RedirectIT.java — GET /{short_code} end to end against PostgreSQL and Redis.
 *
 * Layer: test (integration). Pins the read endpoint's observable contract: 302 with Location equal
 * to the stored long_url (forwarded verbatim, fragment included), Cache-Control: private and an
 * empty body for generated codes, custom aliases and not-yet-expired links (AC-7); 404
 * problem+json of type short-code-not-found for an unknown code (AC-8); 410 problem+json of type
 * short-code-expired for an expired row (AC-9); no Location header on errors (AC-16). Technique:
 * Spring Boot test on Testcontainers Postgres/Redis via AbstractIntegrationTest with a JDK
 * HttpClient that does not follow redirects. Requires Docker; run with ./mvnw -Pit verify.
 */
package com.example.shortener.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortener.domain.CodeSource;
import com.example.shortener.domain.UrlMapping;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /{short_code}} end to end: 302 with {@code Location} = stored {@code long_url} and
 * {@code Cache-Control: private} (AC-7), 404 for an unknown code (AC-8), 410 for an expired code
 * (AC-9); every error is {@code application/problem+json} (AC-16).
 *
 * <p>Fixture strategy: shared application context and containers from {@link
 * AbstractIntegrationTest}. Live links are created through the API ({@code createShortCode} /
 * {@code post}); the expired link is inserted directly with the repository because the API rejects
 * past expiries. Each test starts with an empty table and cache, so every first GET is a cache miss
 * served from Postgres.
 *
 * <p>Removing this class would leave the redirect path unverified over real HTTP: header forwarding
 * by the servlet container, the empty 302 body and the 404/410 rendering through the global handler
 * are only approximated by the MockMvc unit tests.
 */
class RedirectIT extends AbstractIntegrationTest {

  // --- 302 (AC-7) ------------------------------------------------------------------------------

  /**
   * Given a code created for a URL with query string and fragment, when requested, then the answer
   * is 302 with {@code Location} equal to the URL byte for byte, {@code Cache-Control: private} and
   * an empty body (AC-7).
   */
  @Test
  void knownCodeRedirectsWithLocationAndPrivateCacheControl() {
    String longUrl = "https://example.com/some/path?x=1&y=2#frag";
    String shortCode = createShortCode(longUrl);

    HttpResponse<String> response = get("/" + shortCode);

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(header(response, "Location")).isEqualTo(longUrl);
    assertThat(header(response, "Cache-Control")).isEqualTo("private");
    assertThat(response.body()).isEmpty();
  }

  /**
   * Given a link created with {@code custom_alias = aliasIT}, when {@code /aliasIT} is requested,
   * then it redirects to the alias' target with the private cache header.
   */
  @Test
  void customAliasRedirectsToItsLongUrl() {
    HttpResponse<String> created =
        post(URLS_PATH, createRequest("https://example.com/alias-target", "aliasIT", null));
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);

    HttpResponse<String> response = get("/aliasIT");

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(header(response, "Location")).isEqualTo("https://example.com/alias-target");
    assertThat(header(response, "Cache-Control")).isEqualTo("private");
  }

  /**
   * Given a link expiring one hour from now, when requested before that, then it still redirects
   * (302 with the stored URL) rather than answering 410.
   */
  @Test
  void unexpiredCodeRedirectsUntilItsExpiry() {
    String longUrl = uniqueLongUrl();
    Instant expiry = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    HttpResponse<String> created = post(URLS_PATH, createRequest(longUrl, null, expiry.toString()));
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    String shortCode = (String) json(created.body()).get("short_code");

    HttpResponse<String> response = get("/" + shortCode);

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(header(response, "Location")).isEqualTo(longUrl);
  }

  // --- 404 (AC-8) ------------------------------------------------------------------------------

  /**
   * Given a code present in neither cache nor database, when requested, then the answer is a 404
   * RFC 9457 problem whose type ends in {@code short-code-not-found} and carries no {@code
   * Location} header (AC-8, AC-16).
   */
  @Test
  void unknownCodeIs404ProblemJson() {
    HttpResponse<String> response = get("/doesNotExist");

    Map<String, Object> problem = assertProblem(response, 404);
    assertThat(problem.get("type").toString()).endsWith("short-code-not-found");
    assertThat(header(response, "Location")).isNull();
  }

  // --- 410 (AC-9) ------------------------------------------------------------------------------

  /**
   * Given a row inserted directly with {@code expires_at} one day in the past, when its code is
   * requested, then the answer is a 410 problem whose type ends in {@code short-code-expired} and
   * carries no {@code Location} header (AC-9, AC-16).
   */
  @Test
  void expiredCodeIs410ProblemJson() {
    Instant now = Instant.now();
    repository.saveAndFlush(
        new UrlMapping(
            "expiredIT",
            "https://example.com/expired",
            now.minus(2, ChronoUnit.DAYS),
            now.minus(1, ChronoUnit.DAYS),
            CodeSource.REDIS));

    HttpResponse<String> response = get("/expiredIT");

    Map<String, Object> problem = assertProblem(response, 410);
    assertThat(problem.get("type").toString()).endsWith("short-code-expired");
    assertThat(header(response, "Location")).isNull();
  }
}
