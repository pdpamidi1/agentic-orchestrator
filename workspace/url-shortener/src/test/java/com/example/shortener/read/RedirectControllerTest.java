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
 */
class RedirectControllerTest {

  private static final String CODE = "promo2024";
  private static final String LONG_URL = "https://example.com/some/path?x=1&y=2";

  /** Contract values of {@link GlobalExceptionHandler} (package-private there). */
  private static final String PROBLEM_TYPE_BASE = "https://example.com/problems/";

  private static final String DETAIL_INTERNAL_ERROR =
      "An unexpected error occurred while processing the request";

  private final UrlReadService service = mock(UrlReadService.class);

  private final MockMvcTester mvc =
      MockMvcTester.of(
          List.of(new RedirectController(service)),
          builder -> builder.setControllerAdvice(new GlobalExceptionHandler()).build());

  // --- 302 (AC-7) ------------------------------------------------------------------------------

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

  @Test
  void locationIsTheStoredLongUrlVerbatim() {
    String stored = "https://example.com/a%20b/c?q=%C3%A9&r=1#frag";
    when(service.resolve("abc123")).thenReturn(stored);

    MvcTestResult result = get("abc123");

    assertThat(result).hasStatus(HttpStatus.FOUND);
    assertThat(result).headers().hasValue(HttpHeaders.LOCATION, stored);
  }

  // --- 404 (AC-8) ------------------------------------------------------------------------------

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

  @Test
  void postOnTheRedirectRouteIsMethodNotAllowed() {
    MvcTestResult result = mvc.post().uri("/" + CODE).exchange();

    assertProblem(result, HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed", "/" + CODE);
  }

  // --- profile gating (AC-14) ------------------------------------------------------------------

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

  private MvcTestResult get(String shortCode) {
    return mvc.get().uri("/" + shortCode).exchange();
  }

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

  private static String bodyOf(MvcTestResult result) {
    try {
      return result.getResponse().getContentAsString();
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }
}
