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
 */
class RedirectIT extends AbstractIntegrationTest {

  // --- 302 (AC-7) ------------------------------------------------------------------------------

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

  @Test
  void unknownCodeIs404ProblemJson() {
    HttpResponse<String> response = get("/doesNotExist");

    Map<String, Object> problem = assertProblem(response, 404);
    assertThat(problem.get("type").toString()).endsWith("short-code-not-found");
    assertThat(header(response, "Location")).isNull();
  }

  // --- 410 (AC-9) ------------------------------------------------------------------------------

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
