/*
 * RedirectControllerTest.java — MockMvc unit tests for GET /{short_code}
 *
 * Layer: test. Pins the HTTP contract of RedirectController with a standalone MockMvcTester (no
 * Spring context, the Boot 4 @WebMvcTest slice module is not a declared dependency), a Mockito
 * mock of UrlReadService and the real GlobalExceptionHandler so problem+json rendering is tested
 * end to end: 302 + Location verbatim + Cache-Control: private (AC-7), 404 (AC-8), 410 (AC-9), 500
 * without internal details (AC-16), 405 on POST, and the @Profile("!write") gating verified both
 * by reflection and with an ApplicationContextRunner (AC-14).
 */
package com.example.shortener.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.shortener.api.error.GlobalExceptionHandler;
import com.example.shortener.api.error.ShortCodeExpiredException;
import com.example.shortener.api.error.ShortCodeNotFoundException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@link RedirectController} behind a standalone MockMvc together with the real {@link
 * GlobalExceptionHandler} (the Boot 4 {@code @WebMvcTest} slice module is not a declared
 * dependency): 302 + Location + Cache-Control: private (AC-7), 404 (AC-8) and 410 (AC-9)
 * problem+json bodies, 500 (AC-16) and profile gating (AC-14).
 *
 * <p>The service is a Mockito mock, so these tests cover only the adapter: status codes, headers,
 * the absence of a body on 302 and the problem+json shape on errors. Resolution logic (cache,
 * database, expiry) is pinned by {@link UrlReadServiceTest}; the full stack is covered by the
 * {@code RedirectIT} integration test. A fresh mock and tester are created per test instance (JUnit
 * 5 default lifecycle), so tests do not share stubbing.
 */
class RedirectControllerTest {

  /** A short code that looks like a custom alias; used wherever the exact value is irrelevant. */
  private static final String CODE = "promo2024";

  /** A long URL with a query string, to show it is forwarded untouched into {@code Location}. */
  private static final String LONG_URL = "https://example.com/some/path?x=1&y=2";

  /** Contract values of {@link GlobalExceptionHandler} (package-private there). */
  private static final String PROBLEM_TYPE_BASE = "https://example.com/problems/";

  /** The fixed, non-revealing {@code detail} every 500 carries (AC-16). */
  private static final String DETAIL_INTERNAL_ERROR =
      "An unexpected error occurred while processing the request";

  /** Stubbed per test to return a long URL or throw one of the domain exceptions. */
  private final UrlReadService service = mock(UrlReadService.class);

  /**
   * Standalone MockMvc over the controller under test plus the real exception handler, so error
   * responses go through the same {@code problem+json} rendering as in production.
   */
  private final MockMvcTester mvc =
      MockMvcTester.of(
          List.of(new RedirectController(service)),
          builder -> builder.setControllerAdvice(new GlobalExceptionHandler()).build());

  // --- 302 (AC-7) ------------------------------------------------------------------------------

  /**
   * A resolvable code answers 302 Found with {@code Location} = long URL, {@code Cache-Control:
   * private} and an empty body, and the service is asked exactly once.
   */
  @Test
  void knownCodeRedirectsWith302LocationAndPrivateCacheControl() {
    when(service.resolve(CODE)).thenReturn(LONG_URL);

    MvcTestResult result = get(CODE);

    assertThat(result).hasStatus(HttpStatus.FOUND);
    assertThat(result).headers().hasValue(HttpHeaders.LOCATION, LONG_URL);
    assertThat(result).headers().hasValue(HttpHeaders.CACHE_CONTROL, "private");
    assertThat(result).body().isEmpty();
    verify(service).resolve(CODE);
  }

  /**
   * A stored URL containing percent-encoding, a non-ASCII query value and a fragment reaches {@code
   * Location} byte for byte; the controller performs no re-parsing or re-encoding.
   */
  @Test
  void locationIsTheStoredLongUrlVerbatim() {
    String stored = "https://example.com/a%20b/c?q=%C3%A9&r=1#frag";
    when(service.resolve("abc123")).thenReturn(stored);

    MvcTestResult result = get("abc123");

    assertThat(result).hasStatus(HttpStatus.FOUND);
    assertThat(result).headers().hasValue(HttpHeaders.LOCATION, stored);
  }

  // --- 404 (AC-8) ------------------------------------------------------------------------------

  /**
   * {@link ShortCodeNotFoundException} from the service is rendered as a 404 problem+json with the
   * {@code short-code-not-found} type, a detail naming the code and no {@code Location} header.
   */
  @Test
  void unknownCodeIs404ProblemJson() {
    when(service.resolve("nope123")).thenThrow(new ShortCodeNotFoundException("nope123"));

    MvcTestResult result = get("nope123");

    assertProblem(result, HttpStatus.NOT_FOUND, "short-code-not-found", "/nope123");
    assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Short code not found");
    assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("nope123");
    assertThat(result).headers().doesNotContainHeader(HttpHeaders.LOCATION);
  }

  // --- 410 (AC-9) ------------------------------------------------------------------------------

  /**
   * {@link ShortCodeExpiredException} from the service is rendered as a 410 problem+json with the
   * {@code short-code-expired} type, a detail naming the code and its expiry instant, and no {@code
   * Location} header.
   */
  @Test
  void expiredCodeIs410ProblemJson() {
    Instant expiredAt = Instant.parse("2020-01-01T00:00:00Z");
    when(service.resolve("old456")).thenThrow(new ShortCodeExpiredException("old456", expiredAt));

    MvcTestResult result = get("old456");

    assertProblem(result, HttpStatus.GONE, "short-code-expired", "/old456");
    assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Short code expired");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.detail")
        .asString()
        .contains("old456", "2020-01-01T00:00:00Z");
    assertThat(result).headers().doesNotContainHeader(HttpHeaders.LOCATION);
  }

  // --- 500 (AC-16) -----------------------------------------------------------------------------

  /**
   * Any other exception from the service becomes a 500 problem+json with the fixed generic detail;
   * the exception message, class name and package never appear anywhere in the body.
   */
  @Test
  void unexpectedServiceFailureIs500ProblemWithoutInternalDetails() {
    when(service.resolve(CODE)).thenThrow(new IllegalStateException("connection pool exhausted"));

    MvcTestResult result = get(CODE);

    assertProblem(result, HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "/" + CODE);
    assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo(DETAIL_INTERNAL_ERROR);
    assertThat(bodyOf(result))
        .doesNotContain("connection pool exhausted")
        .doesNotContain("IllegalStateException")
        .doesNotContain("com.example");
  }

  // --- request shape -------------------------------------------------------------------------

  /**
   * The redirect route accepts GET only; a POST is a 405 problem+json produced by the exception
   * handler's Spring MVC path, with {@code instance} set to the request path.
   */
  @Test
  void postOnTheRedirectRouteIsMethodNotAllowed() {
    MvcTestResult result = mvc.post().uri("/" + CODE).exchange();

    assertProblem(result, HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed", "/" + CODE);
  }

  // --- profile gating (AC-14) ------------------------------------------------------------------

  /**
   * Reflection check of the wiring contract: the class is a {@code @RestController} gated on
   * exactly {@code !write}, and {@code redirectToLongUrl} is a GET mapped to {@code /{short_code}}.
   */
  @Test
  void controllerIsGatedOnNotWriteProfileAndMapsGetShortCode() throws NoSuchMethodException {
    Profile profile = RedirectController.class.getAnnotation(Profile.class);
    assertThat(profile).isNotNull();
    assertThat(profile.value()).containsExactly("!write");
    assertThat(RedirectController.class.isAnnotationPresent(RestController.class)).isTrue();
    GetMapping mapping =
        RedirectController.class
            .getMethod("redirectToLongUrl", String.class)
            .getAnnotation(GetMapping.class);
    assertThat(mapping).isNotNull();
    assertThat(mapping.path()).containsExactly("/{short_code}");
  }

  /**
   * Behavioural check of the gate with a minimal Spring context: the bean is missing under {@code
   * spring.profiles.active=write} and present under {@code read} and under no profile at all.
   */
  @Test
  void controllerIsAbsentUnderTheWriteProfileAndPresentOtherwise() {
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withBean(UrlReadService.class, () -> mock(UrlReadService.class))
            .withUserConfiguration(RedirectController.class);

    runner
        .withPropertyValues("spring.profiles.active=write")
        .run(context -> assertThat(context).doesNotHaveBean(RedirectController.class));
    runner
        .withPropertyValues("spring.profiles.active=read")
        .run(context -> assertThat(context).hasSingleBean(RedirectController.class));
    runner.run(context -> assertThat(context).hasSingleBean(RedirectController.class));
  }

  // --- helpers ---------------------------------------------------------------------------------

  /**
   * Performs {@code GET /{shortCode}} against the standalone MockMvc.
   *
   * @param shortCode the path segment to request
   * @return the exchange result for AssertJ assertions
   */
  private MvcTestResult get(String shortCode) {
    return mvc.get().uri("/" + shortCode).exchange();
  }

  /**
   * Asserts the RFC 9457 shape shared by every error response: status, {@code
   * application/problem+json}, the stable {@code type} URI, non-blank title and detail, {@code
   * instance} = request path, and none of the fields that would leak internals.
   *
   * @param result the exchange result
   * @param status the expected HTTP status
   * @param typeSlug the last segment of the expected problem {@code type}
   * @param instance the expected {@code instance} (request path)
   */
  private static void assertProblem(
      MvcTestResult result, HttpStatus status, String typeSlug, String instance) {
    assertThat(result).hasStatus(status).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(status.value());
    assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(PROBLEM_TYPE_BASE + typeSlug);
    assertThat(result).bodyJson().extractingPath("$.title").asString().isNotBlank();
    assertThat(result).bodyJson().extractingPath("$.detail").asString().isNotBlank();
    assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo(instance);
    assertThat(result).bodyJson().doesNotHavePath("$.stackTrace");
    assertThat(result).bodyJson().doesNotHavePath("$.trace");
    assertThat(result).bodyJson().doesNotHavePath("$.exception");
  }

  /**
   * Reads the raw response body as a string for negative substring checks that a JSON-path
   * assertion cannot express.
   *
   * @param result the exchange result
   * @return the body text
   */
  private static String bodyOf(MvcTestResult result) {
    try {
      return result.getResponse().getContentAsString();
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }
}
