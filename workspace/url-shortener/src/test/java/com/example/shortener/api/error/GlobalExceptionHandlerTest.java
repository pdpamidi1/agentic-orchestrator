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
 */
class GlobalExceptionHandlerTest {

  /** Marker message of an unexpected server-side failure; it must never appear in a response. */
  private static final String INTERNAL_MESSAGE = "internal-detail-that-must-not-leak";

  @RestController
  static class ThrowingController {

    @PostMapping(path = "/api/v1/urls", consumes = MediaType.APPLICATION_JSON_VALUE)
    String create(@Valid @RequestBody CreateUrlRequest request) {
      return "created";
    }

    @GetMapping("/throw/invalid-url")
    String invalidUrl() {
      throw new InvalidUrlException("URL host must not be empty");
    }

    @GetMapping("/throw/not-found")
    String notFound() {
      throw new ShortCodeNotFoundException("nope123");
    }

    @GetMapping("/throw/conflict")
    String conflict() {
      throw new AliasAlreadyExistsException("promo2024");
    }

    @GetMapping("/throw/expired")
    String expired() {
      throw new ShortCodeExpiredException("old456", Instant.parse("2020-01-01T00:00:00Z"));
    }

    @GetMapping("/throw/boom")
    String boom() {
      throw new IllegalStateException(INTERNAL_MESSAGE);
    }

    @GetMapping("/throw/error")
    String error() {
      throw new StackOverflowError(INTERNAL_MESSAGE);
    }
  }

  private final MockMvcTester mvc =
      MockMvcTester.of(
          List.of(new ThrowingController()),
          builder -> builder.setControllerAdvice(new GlobalExceptionHandler()).build());

  // --- 400 -------------------------------------------------------------------------------------

  @Test
  void invalidLongUrlSchemeIsBadRequestProblem() {
    MvcTestResult result = post("{\"long_url\":\"ftp://example.com/file\"}");

    assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error", "Validation failed");
    assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("long_url");
    assertThat(result).bodyJson().extractingPath("$.detail").asString().doesNotContain("longUrl");
  }

  @Test
  void tooLongUrlIsBadRequestProblem() {
    String url = "https://example.com/" + "a".repeat(2048);
    MvcTestResult result = post("{\"long_url\":\"" + url + "\"}");

    assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error", "Validation failed");
    assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("long_url");
  }

  @Test
  void invalidAliasIsBadRequestProblem() {
    MvcTestResult result =
        post("{\"long_url\":\"https://example.com\",\"custom_alias\":\"bad-alias\"}");

    assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error", "Validation failed");
    assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("custom_alias");
  }

  @Test
  void pastExpirationDateIsBadRequestProblem() {
    MvcTestResult result =
        post("{\"long_url\":\"https://example.com\",\"expiration_date\":\"2001-01-01T00:00:00Z\"}");

    assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error", "Validation failed");
    assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("expiration_date");
  }

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

  @Test
  void unreadableJsonBodyIsBadRequestProblem() {
    MvcTestResult result = post("{ this is not json");

    assertProblem(result, HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request");
    assertThat(body(result)).doesNotContainIgnoringCase("jackson");
  }

  @Test
  void emptyBodyIsBadRequestProblem() {
    MvcTestResult result = post("");

    assertProblem(result, HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request");
  }

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

  @Test
  void shortCodeNotFoundIsNotFoundProblem() {
    MvcTestResult result = mvc.get().uri("/throw/not-found").exchange();

    assertProblem(result, HttpStatus.NOT_FOUND, "short-code-not-found", "Short code not found");
    assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("nope123");
    assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/throw/not-found");
  }

  @Test
  void aliasAlreadyExistsIsConflictProblem() {
    MvcTestResult result = mvc.get().uri("/throw/conflict").exchange();

    assertProblem(result, HttpStatus.CONFLICT, "alias-already-exists", "Alias already exists");
    assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("promo2024");
    assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/throw/conflict");
  }

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

  @Test
  void unhandledErrorIsInternalServerErrorProblemWithoutLeak() {
    MvcTestResult result = mvc.get().uri("/throw/error").exchange();

    assertProblem(
        result, HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal server error");
    assertNoLeak(result);
  }

  // --- Spring MVC exceptions handled by the superclass keep the same shape --------------------

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

  @Test
  void methodNotAllowedIsProblemJsonToo() {
    MvcTestResult result = mvc.delete().uri("/api/v1/urls").exchange();

    assertProblem(
        result, HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed", "Method Not Allowed");
  }

  // --- helpers ---------------------------------------------------------------------------------

  @Test
  void jsonNameConvertsCamelCaseToSnakeCase() {
    assertThat(GlobalExceptionHandler.jsonName("longUrl")).isEqualTo("long_url");
    assertThat(GlobalExceptionHandler.jsonName("customAlias")).isEqualTo("custom_alias");
    assertThat(GlobalExceptionHandler.jsonName("expirationDate")).isEqualTo("expiration_date");
    assertThat(GlobalExceptionHandler.jsonName("plain")).isEqualTo("plain");
  }

  private MvcTestResult post(String body) {
    return mvc.post()
        .uri("/api/v1/urls")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }

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

  private static String body(MvcTestResult result) {
    try {
      return result.getResponse().getContentAsString();
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

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
