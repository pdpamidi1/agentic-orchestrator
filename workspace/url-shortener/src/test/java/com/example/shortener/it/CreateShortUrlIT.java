/*
 * CreateShortUrlIT.java — POST /api/v1/urls end to end against PostgreSQL and Redis.
 *
 * Layer: test (integration). Pins the write endpoint's observable contract: 201 with the exact
 * response keys, short_url built from the configured base URL, a persisted row without creator
 * (AC-1), custom_alias used verbatim as the code (AC-3 happy path), 400 problem+json with nothing
 * persisted for twelve kinds of invalid input (AC-2, AC-5, AC-6, AC-16), 409 on a taken alias with
 * the existing row unchanged (AC-3) and distinct codes for the same long_url (AC-4). Technique:
 * Spring Boot test on Testcontainers Postgres/Redis via AbstractIntegrationTest, JDK HttpClient,
 * repository checks for persistence. Requires Docker; run with ./mvnw -Pit verify (failsafe).
 */
package com.example.shortener.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortener.domain.UrlMapping;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code POST /api/v1/urls} end to end against PostgreSQL and Redis: 201 + response shape +
 * persisted row (AC-1, AC-2), 400 problem+json with nothing persisted (AC-5, AC-6, AC-16), 409 on a
 * taken alias with the existing mapping unchanged (AC-3) and distinct codes for a repeated {@code
 * long_url} (AC-4).
 *
 * <p>Fixture strategy: shared application context and containers from {@link
 * AbstractIntegrationTest}; the {@code urls} table is emptied before each test, which is what makes
 * the "nothing persisted" assertion ({@code repository.count() == 0}) meaningful. Requests are
 * built with {@code createRequest} or as literal JSON for the malformed cases; persistence is
 * verified through the autowired repository rather than through a second HTTP call.
 *
 * <p>Removing this class would leave the write path unverified against a real database: the
 * primary-key conflict path, transactional rollback on 400/409 and the {@code code_source} column
 * round trip are not reachable from the MockMvc unit tests.
 */
class CreateShortUrlIT extends AbstractIntegrationTest {

  /** Shape of any short code: the migration's {@code urls_short_code_format_chk} constraint. */
  private static final Pattern SHORT_CODE = Pattern.compile("^[0-9a-zA-Z]{3,32}$");

  /** A valid target used where the test does not need a unique URL. */
  private static final String VALID_URL = "https://example.com/landing?campaign=it";

  // --- 201 (AC-1) ------------------------------------------------------------------------------

  /**
   * Given a valid {@code long_url} and an expiry one day ahead, when posted, then the response is
   * 201 {@code application/json} with exactly the five contract keys, a well-formed generated code,
   * {@code short_url = BASE_URL/<code>}, the same value in the {@code Location} header, {@code
   * code_source = redis}; and the {@code urls} row holds the URL, the expiry, a creation timestamp,
   * a {@code null} creator and the same code source (AC-1).
   */
  @Test
  void createsShortUrlAndPersistsRowWithoutCreator() {
    Instant expiry = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    String longUrl = uniqueLongUrl();

    HttpResponse<String> response =
        post(URLS_PATH, createRequest(longUrl, null, expiry.toString()));

    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    assertThat(contentType(response)).startsWith("application/json");
    Map<String, Object> body = json(response.body());
    assertThat(body)
        .containsOnlyKeys("short_url", "short_code", "long_url", "expires_at", "code_source");
    String shortCode = (String) body.get("short_code");
    assertThat(shortCode).matches(SHORT_CODE);
    assertThat(body.get("short_url")).isEqualTo(BASE_URL + "/" + shortCode);
    assertThat(body.get("long_url")).isEqualTo(longUrl);
    assertThat(Instant.parse((String) body.get("expires_at"))).isEqualTo(expiry);
    assertThat(body.get("code_source")).isEqualTo("redis");
    assertThat(header(response, "Location")).isEqualTo(BASE_URL + "/" + shortCode);

    UrlMapping row = repository.findByShortCode(shortCode).orElseThrow();
    assertThat(row.getLongUrl()).isEqualTo(longUrl);
    assertThat(row.getExpiresAt()).isEqualTo(expiry);
    assertThat(row.getCreatedAt()).isNotNull();
    assertThat(row.getCreatedBy()).isNull();
    assertThat(row.getCodeSource().wireValue()).isEqualTo(body.get("code_source"));
  }

  /**
   * Given a request without {@code expiration_date}, when posted, then the 201 body still contains
   * the {@code expires_at} key with a JSON {@code null} and the row's {@code expires_at} is NULL.
   */
  @Test
  void linkWithoutExpiryHasNullExpiresAt() {
    HttpResponse<String> response = post(URLS_PATH, createRequest(VALID_URL, null, null));

    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    Map<String, Object> body = json(response.body());
    assertThat(body).containsKey("expires_at");
    assertThat(body.get("expires_at")).isNull();
    String shortCode = (String) body.get("short_code");
    assertThat(repository.findByShortCode(shortCode).orElseThrow().getExpiresAt()).isNull();
  }

  // --- custom alias (AC-2) ---------------------------------------------------------------------

  /**
   * Given {@code custom_alias = promoIT2026}, when posted, then the alias is the {@code short_code}
   * verbatim, {@code short_url} ends with it and the row is stored under that key.
   */
  @Test
  void customAliasBecomesTheShortCode() {
    HttpResponse<String> response = post(URLS_PATH, createRequest(VALID_URL, "promoIT2026", null));

    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    Map<String, Object> body = json(response.body());
    assertThat(body.get("short_code")).isEqualTo("promoIT2026");
    assertThat(body.get("short_url")).isEqualTo(BASE_URL + "/promoIT2026");
    UrlMapping row = repository.findByShortCode("promoIT2026").orElseThrow();
    assertThat(row.getLongUrl()).isEqualTo(VALID_URL);
    assertThat(row.getCreatedBy()).isNull();
  }

  // --- 400 (AC-5, AC-6, AC-16) -----------------------------------------------------------------

  /**
   * Invalid request bodies, each paired with a display name: wrong or missing scheme, blank or
   * absent {@code long_url}, over-long URL, alias too short / too long / with forbidden characters,
   * past or unparseable {@code expiration_date}, truncated JSON and a JSON array.
   *
   * @return (description, raw body) pairs consumed by the parameterized 400 test below
   */
  static Stream<Arguments> invalidRequests() {
    return Stream.of(
        Arguments.of("non-http scheme", createRequest("ftp://example.com/file", null, null)),
        Arguments.of("missing scheme", createRequest("example.com/path", null, null)),
        Arguments.of("blank long_url", createRequest("   ", null, null)),
        Arguments.of("missing long_url", "{\"custom_alias\":\"abc123\"}"),
        Arguments.of(
            "long_url over 2048 characters",
            createRequest("https://example.com/" + "a".repeat(2040), null, null)),
        Arguments.of("custom_alias too short", createRequest(VALID_URL, "ab", null)),
        Arguments.of("custom_alias too long", createRequest(VALID_URL, "a".repeat(33), null)),
        Arguments.of(
            "custom_alias with forbidden characters", createRequest(VALID_URL, "bad-alias!", null)),
        Arguments.of(
            "expiration_date in the past", createRequest(VALID_URL, null, "2000-01-01T00:00:00Z")),
        Arguments.of("malformed expiration_date", createRequest(VALID_URL, null, "next-week")),
        Arguments.of("unreadable JSON body", "{\"long_url\": "),
        Arguments.of("JSON array instead of object", "[\"https://example.com\"]"));
  }

  /**
   * Given each invalid body from {@link #invalidRequests()}, when posted, then the answer is a 400
   * RFC 9457 problem with an {@code https://} type URI and the {@code urls} table stays empty
   * (AC-2, AC-5, AC-6, AC-16).
   *
   * @param description human-readable case name (shown in the test report)
   * @param body the raw request body
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidRequests")
  void invalidRequestIs400ProblemJsonAndPersistsNothing(String description, String body) {
    HttpResponse<String> response = post(URLS_PATH, body);

    Map<String, Object> problem = assertProblem(response, 400);
    assertThat(problem.get("type").toString()).startsWith("https://");
    assertThat(repository.count()).as("nothing persisted for: %s", description).isZero();
  }

  // --- 409 (AC-3) ------------------------------------------------------------------------------

  /**
   * Given alias {@code takenIT} already mapped to one URL, when a second request tries to map it to
   * another URL, then the answer is a 409 problem of type {@code alias-already-exists}, the stored
   * row still has the original URL, creation time and code source, and the table holds exactly one
   * row (AC-3).
   */
  @Test
  void takenAliasIs409AndLeavesTheExistingMappingUnchanged() {
    String original = "https://example.com/original";
    HttpResponse<String> first = post(URLS_PATH, createRequest(original, "takenIT", null));
    assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
    UrlMapping before = repository.findByShortCode("takenIT").orElseThrow();

    HttpResponse<String> second =
        post(URLS_PATH, createRequest("https://example.com/other", "takenIT", null));

    Map<String, Object> problem = assertProblem(second, 409);
    assertThat(problem.get("type").toString()).endsWith("alias-already-exists");
    UrlMapping after = repository.findByShortCode("takenIT").orElseThrow();
    assertThat(after.getLongUrl()).isEqualTo(original);
    assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
    assertThat(after.getCodeSource()).isEqualTo(before.getCodeSource());
    assertThat(repository.count()).isEqualTo(1);
  }

  // --- no deduplication (AC-4) -----------------------------------------------------------------

  /**
   * Given the same {@code long_url} posted twice without alias, when both succeed, then the two
   * codes differ and both rows exist: identical targets are never deduplicated (AC-4).
   */
  @Test
  void repeatedLongUrlGetsDistinctShortCodes() {
    String longUrl = uniqueLongUrl();

    String firstCode = createShortCode(longUrl);
    String secondCode = createShortCode(longUrl);

    assertThat(secondCode).isNotEqualTo(firstCode);
    assertThat(repository.findByShortCode(firstCode)).isPresent();
    assertThat(repository.findByShortCode(secondCode)).isPresent();
    assertThat(repository.count()).isEqualTo(2);
  }
}
