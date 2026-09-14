/*
 * GlobalExceptionHandlerTest.java — problem+json rendering of every mapped exception.
 *
 * Layer: test (unit). Pins the single error contract of the API (AC-16): each application
 * exception, each Spring MVC failure (Bean Validation, unreadable body, unsupported media type,
 * method not allowed) and every unexpected Throwable is rendered by GlobalExceptionHandler with the
 * right status, a stable type URI, title, detail and instance, Content-Type
 * application/problem+json and without leaking class names or stack traces (also AC-2, AC-5,
 * AC-6 for the 400 cases). Technique: standalone MockMvc (MockMvcTester) around a throwing test
 * controller; no Spring Boot context, no database, no Redis. Run with ./mvnw test.
 */
package com.example.shortener.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortener.api.dto.CreateUrlRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every mapped exception is rendered by {@link GlobalExceptionHandler} as {@code
 * application/problem+json} with a consistent body and no internal details (AC-2, AC-5, AC-6,
 * AC-16). Uses a standalone MockMvc with a throwing test controller; no Spring Boot context.
 *
 * <p>Fixture strategy: each test instance builds its own {@link MockMvcTester} from a fresh {@link
 * ThrowingController} and a fresh {@link GlobalExceptionHandler} registered as controller advice
 * (standalone setup, so only the advice under test and MVC's default message converters and
 * validator are involved). The controller mimics the real {@code POST /api/v1/urls} signature with
 * {@code @Valid} to provoke Bean Validation and message-conversion failures, and exposes one {@code
 * GET /throw/*} route per exception type, so each 4xx/5xx path is reached exactly as in the
 * application without any of the real services. Assertions read the JSON body with the AssertJ
 * MockMvc integration; the constants of the handler ({@code PROBLEM_TYPE_BASE}, the fixed detail
 * texts) are the expected values.
 *
 * <p>Removing this class would leave the status mapping, the type slugs and titles, the forced
 * content type, the {@code instance} member and, most importantly, the no-leak guarantee for
 * unexpected exceptions and errors (500) untested: the integration tests exercise only 400, 404,
 * 409 and 410 and never a 500, 405 or 415.
 */
class GlobalExceptionHandlerTest {

  /** Marker message of an unexpected server-side failure; it must never appear in a response. */
  private static final String INTERNAL_MESSAGE = "internal-detail-that-must-not-leak";

  /**
   * Minimal controller standing in for the real endpoints: one JSON-consuming POST with a validated
   * {@link CreateUrlRequest} body (triggers 400s and 415) and one GET per exception the handler
   * maps, each throwing unconditionally. Registered only in this test's standalone MockMvc.
   */
  @RestController
  static class ThrowingController {

    /** Real-shaped write route; never reached with an invalid body because {@code @Valid} fails. */
    @PostMapping(path = "/api/v1/urls", consumes = MediaType.APPLICATION_JSON_VALUE)
    String create(@Valid @RequestBody CreateUrlRequest request) {
      return "created";
    }

    /** Raises the 400 "invalid-url" application exception with a client-facing message. */
    @GetMapping("/throw/invalid-url")
    String invalidUrl() {
      throw new InvalidUrlException("URL host must not be empty");
    }

    /** Raises the 404 "short-code-not-found" application exception. */
    @GetMapping("/throw/not-found")
    String notFound() {
      throw new ShortCodeNotFoundException("nope123");
    }

    /** Raises the 409 "alias-already-exists" application exception. */
    @GetMapping("/throw/conflict")
    String conflict() {
      throw new AliasAlreadyExistsException("promo2024");
    }

    /** Raises the 410 "short-code-expired" application exception with a known expiry instant. */
    @GetMapping("/throw/expired")
    String expired() {
      throw new ShortCodeExpiredException("old456", Instant.parse("2020-01-01T00:00:00Z"));
    }

    /** Raises an unchecked exception no handler maps explicitly: the 500 path for Exceptions. */
    @GetMapping("/throw/boom")
    String boom() {
      throw new IllegalStateException(INTERNAL_MESSAGE);
    }

    /** Raises an {@link Error} (not an Exception): the 500 path must cover any Throwable. */
    @GetMapping("/throw/error")
    String error() {
      throw new StackOverflowError(INTERNAL_MESSAGE);
    }
  }

  /**
   * Standalone MockMvc: only {@link ThrowingController} and a real {@link GlobalExceptionHandler}
   * as controller advice, built per test instance (no shared state between tests).
   */
  private final MockMvcTester mvc =
      MockMvcTester.of(
          List.of(new ThrowingController()),
          builder -> builder.setControllerAdvice(new GlobalExceptionHandler()).build());

  // --- 400 -------------------------------------------------------------------------------------

  /**
   * Given a body whose {@code long_url} uses the ftp scheme, when posted, then a 400
   * "validation-error" problem is returned whose detail names the JSON field {@code long_url}
   * rather than the Java property {@code longUrl}.
   */
  @Test
  void invalidLongUrlSchemeIsBadRequestProblem() {
    MvcTestResult result = post("{\"long_url\":\"ftp://example.com/file\"}");

    assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error", "Validation failed");
    assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("long_url");
    assertThat(result).bodyJson().extractingPath("$.detail").asString().doesNotContain("longUrl");
  }

  /**
   * Given a {@code long_url} well over 2048 characters, when posted, then a 400 "validation-error"
   * problem names {@code long_url} in its detail.
   */
  @Test
  void tooLongUrlIsBadRequestProblem() {
    String url = "https://example.com/" + "a".repeat(2048);
    MvcTestResult result = post("{\"long_url\":\"" + url + "\"}");

    assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error", "Validation failed");
    assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("long_url");
  }

  /**
   * Given a {@code custom_alias} containing a hyphen, when posted, then a 400 "validation-error"
   * problem names {@code custom_alias} in its detail (AC-5).
   */
  @Test
  void invalidAliasIsBadRequestProblem() {
    MvcTestResult result =
        post("{\"long_url\":\"https://example.com\",\"custom_alias\":\"bad-alias\"}");

    assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error", "Validation failed");
    assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("custom_alias");
  }

  /**
   * Given a well-formed {@code expiration_date} in the past, when posted, then a 400
   * "validation-error" problem names {@code expiration_date} in its detail (AC-6).
   */
  @Test
  void pastExpirationDateIsBadRequestProblem() {
    MvcTestResult result =
        post("{\"long_url\":\"https://example.com\",\"expiration_date\":\"2001-01-01T00:00:00Z\"}");

    assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error", "Validation failed");
    assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("expiration_date");
  }

  /**
   * Given a body violating all three fields, when posted, then the single 400 detail lists every
   * field as {@code <json_name>: <message>} entries instead of reporting only the first failure.
   */
  @Test
  void multipleViolationsAreListedInDetail() {
    MvcTestResult result =
        post(
            "{\"long_url\":\"ftp://x\",\"custom_alias\":\"!!\","
                + "\"expiration_date\":\"2001-01-01T00:00:00Z\"}");

    assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error", "Validation failed");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.detail")
        .asString()
        .contains("custom_alias: ", "expiration_date: ", "long_url: ");
  }

  /**
   * Given an {@code expiration_date} that is not ISO-8601 ("tomorrow"), when posted, then Jackson's
   * failure is rendered as a 400 "malformed-request" problem with the handler's fixed detail, and
   * neither Jackson's class names nor the JSON location appear anywhere in the body (AC-6).
   */
  @Test
  void malformedIso8601ExpirationDateIsBadRequestProblem() {
    MvcTestResult result =
        post("{\"long_url\":\"https://example.com\",\"expiration_date\":\"tomorrow\"}");

    assertProblem(result, HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo(GlobalExceptionHandler.DETAIL_MALFORMED_REQUEST);
    // Jackson's own message (class names, line/column) must not be forwarded.
    assertThat(body(result))
        .doesNotContainIgnoringCase("jackson")
        .doesNotContain("OffsetDateTime")
        .doesNotContain("line:");
  }

  /**
   * Given a body that is not JSON at all, when posted, then a 400 "malformed-request" problem is
   * returned without any Jackson wording.
   */
  @Test
  void unreadableJsonBodyIsBadRequestProblem() {
    MvcTestResult result = post("{ this is not json");

    assertProblem(result, HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request");
    assertThat(body(result)).doesNotContainIgnoringCase("jackson");
  }

  /**
   * Given an empty request body on a route that requires one, when posted, then the missing body is
   * reported as a 400 "malformed-request" problem, not as a 500.
   */
  @Test
  void emptyBodyIsBadRequestProblem() {
    MvcTestResult result = post("");

    assertProblem(result, HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request");
  }

  /**
   * Given a controller throwing {@link InvalidUrlException}, when called, then a 400 "invalid-url"
   * problem carries the exception message verbatim as detail and the request path as instance.
   */
  @Test
  void invalidUrlExceptionIsBadRequestProblem() {
    MvcTestResult result = mvc.get().uri("/throw/invalid-url").exchange();

    assertProblem(result, HttpStatus.BAD_REQUEST, "invalid-url", "Invalid URL");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("URL host must not be empty");
    assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/throw/invalid-url");
  }

  // --- 404 / 409 / 410 -------------------------------------------------------------------------

  /**
   * Given a controller throwing {@link ShortCodeNotFoundException}, when called, then a 404
   * "short-code-not-found" problem names the unknown code in its detail.
   */
  @Test
  void shortCodeNotFoundIsNotFoundProblem() {
    MvcTestResult result = mvc.get().uri("/throw/not-found").exchange();

    assertProblem(result, HttpStatus.NOT_FOUND, "short-code-not-found", "Short code not found");
    assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("nope123");
    assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/throw/not-found");
  }

  /**
   * Given a controller throwing {@link AliasAlreadyExistsException}, when called, then a 409
   * "alias-already-exists" problem names the taken alias in its detail.
   */
  @Test
  void aliasAlreadyExistsIsConflictProblem() {
    MvcTestResult result = mvc.get().uri("/throw/conflict").exchange();

    assertProblem(result, HttpStatus.CONFLICT, "alias-already-exists", "Alias already exists");
    assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("promo2024");
    assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/throw/conflict");
  }

  /**
   * Given a controller throwing {@link ShortCodeExpiredException} with an expiry instant, when
   * called, then a 410 "short-code-expired" problem names both the code and the instant.
   */
  @Test
  void shortCodeExpiredIsGoneProblem() {
    MvcTestResult result = mvc.get().uri("/throw/expired").exchange();

    assertProblem(result, HttpStatus.GONE, "short-code-expired", "Short code expired");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.detail")
        .asString()
        .contains("old456", "2020-01-01T00:00:00Z");
    assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/throw/expired");
  }

  // --- 500 -------------------------------------------------------------------------------------

  /**
   * Given a controller throwing an unmapped {@link IllegalStateException}, when called, then a 500
   * "internal-error" problem uses the fixed generic detail and the body contains neither the
   * exception message nor any class or package name.
   */
  @Test
  void unhandledExceptionIsInternalServerErrorProblemWithoutLeak() {
    MvcTestResult result = mvc.get().uri("/throw/boom").exchange();

    assertProblem(
        result, HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal server error");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo(GlobalExceptionHandler.DETAIL_INTERNAL_ERROR);
    assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/throw/boom");
    assertNoLeak(result);
  }

  /**
   * Given a controller throwing an {@link Error} ({@link StackOverflowError}), when called, then
   * the {@code Throwable} handler still produces the same 500 problem without leaking details.
   */
  @Test
  void unhandledErrorIsInternalServerErrorProblemWithoutLeak() {
    MvcTestResult result = mvc.get().uri("/throw/error").exchange();

    assertProblem(
        result, HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal server error");
    assertNoLeak(result);
  }

  // --- Spring MVC exceptions handled by the superclass keep the same shape --------------------

  /**
   * Given a {@code text/plain} body on the JSON-only route, when posted, then the 415 produced by
   * {@code ResponseEntityExceptionHandler} is still rendered as problem+json with a type slug
   * derived from the reason phrase and the request path as instance.
   */
  @Test
  void unsupportedMediaTypeIsProblemJsonToo() {
    MvcTestResult result =
        mvc.post()
            .uri("/api/v1/urls")
            .contentType(MediaType.TEXT_PLAIN)
            .content("long_url=https://example.com")
            .exchange();

    assertProblem(
        result,
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "unsupported-media-type",
        "Unsupported Media Type");
    assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/api/v1/urls");
  }

  /**
   * Given a DELETE on a POST-only route, when sent, then the 405 from the superclass is rendered as
   * a "method-not-allowed" problem with the standard shape.
   */
  @Test
  void methodNotAllowedIsProblemJsonToo() {
    MvcTestResult result = mvc.delete().uri("/api/v1/urls").exchange();

    assertProblem(
        result, HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed", "Method Not Allowed");
  }

  // --- helpers ---------------------------------------------------------------------------------

  /**
   * Given the three request property names and a single-word name, when converted with {@code
   * jsonName}, then camelCase becomes snake_case and a name without capitals is unchanged.
   */
  @Test
  void jsonNameConvertsCamelCaseToSnakeCase() {
    assertThat(GlobalExceptionHandler.jsonName("longUrl")).isEqualTo("long_url");
    assertThat(GlobalExceptionHandler.jsonName("customAlias")).isEqualTo("custom_alias");
    assertThat(GlobalExceptionHandler.jsonName("expirationDate")).isEqualTo("expiration_date");
    assertThat(GlobalExceptionHandler.jsonName("plain")).isEqualTo("plain");
  }

  /**
   * Posts a raw JSON body to the write route.
   *
   * @param body the request body, sent as {@code application/json}
   * @return the exchanged result
   */
  private MvcTestResult post(String body) {
    return mvc.post()
        .uri("/api/v1/urls")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }

  /**
   * Asserts the invariant shape of every error response: expected status, {@code
   * application/problem+json}, {@code status} member, {@code type} = {@code PROBLEM_TYPE_BASE} +
   * slug, the given title, non-blank {@code detail} and {@code instance}, and none of the members
   * Spring Boot's default error body would add ({@code stackTrace}, {@code trace}, {@code
   * exception}, {@code message}).
   *
   * @param result the exchanged result
   * @param status the expected HTTP status
   * @param typeSlug the last path segment of the expected {@code type} URI
   * @param title the expected {@code title}
   */
  private static void assertProblem(
      MvcTestResult result, HttpStatus status, String typeSlug, String title) {
    assertThat(result).hasStatus(status).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(status.value());
    assertThat(result)
        .bodyJson()
        .extractingPath("$.type")
        .isEqualTo(GlobalExceptionHandler.PROBLEM_TYPE_BASE + typeSlug);
    assertThat(result).bodyJson().extractingPath("$.title").isEqualTo(title);
    assertThat(result).bodyJson().extractingPath("$.detail").asString().isNotBlank();
    assertThat(result).bodyJson().extractingPath("$.instance").asString().isNotBlank();
    assertThat(result).bodyJson().doesNotHavePath("$.stackTrace");
    assertThat(result).bodyJson().doesNotHavePath("$.trace");
    assertThat(result).bodyJson().doesNotHavePath("$.exception");
    assertThat(result).bodyJson().doesNotHavePath("$.message");
  }

  /**
   * Reads the response body as a string for substring assertions.
   *
   * @param result the exchanged result
   * @return the raw body
   */
  private static String body(MvcTestResult result) {
    try {
      return result.getResponse().getContentAsString();
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Asserts that a 500 body carries no internal identifier: not the exception message, not the
   * exception class names thrown by the fixture, not the controller name and no {@code java.lang}
   * or {@code com.example} package prefix.
   *
   * @param result the exchanged result
   */
  private static void assertNoLeak(MvcTestResult result) {
    assertThat(body(result))
        .doesNotContain(INTERNAL_MESSAGE)
        .doesNotContain("IllegalStateException")
        .doesNotContain("StackOverflowError")
        .doesNotContain("ThrowingController")
        .doesNotContain("java.lang")
        .doesNotContain("com.example");
  }
}
