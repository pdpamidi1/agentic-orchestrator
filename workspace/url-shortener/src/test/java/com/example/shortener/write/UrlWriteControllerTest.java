/*
 * UrlWriteControllerTest.java — MockMvc unit tests for POST /api/v1/urls
 *
 * Layer: test. Pins the HTTP contract of UrlWriteController with a standalone MockMvcTester (no
 * Spring context), a Mockito mock of UrlWriteService and the real GlobalExceptionHandler: the 201
 * snake_case payload plus Location header (AC-1), Bean Validation and unreadable-body rejections
 * as 400 problem+json that never reach the service (AC-2, AC-5, AC-6: nothing persisted), the
 * service's own InvalidUrlException as 400, alias conflict as 409 (AC-3), 500 without internal
 * details (AC-16), 415 / 405 request-shape errors, and the @Profile("!read") gating checked by
 * reflection and with an ApplicationContextRunner (AC-14).
 */
package com.example.shortener.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.shortener.api.dto.CreateUrlRequest;
import com.example.shortener.api.dto.CreateUrlResponse;
import com.example.shortener.api.error.AliasAlreadyExistsException;
import com.example.shortener.api.error.GlobalExceptionHandler;
import com.example.shortener.api.error.InvalidUrlException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@link UrlWriteController} behind a standalone MockMvc together with the real {@link
 * GlobalExceptionHandler} (the Boot 4 {@code @WebMvcTest} slice module is not a declared
 * dependency): 201 payload (AC-1), 400 problem+json with no persistence (AC-5 and the other
 * validation rules), 409 (AC-3), 500 (AC-16) and profile gating (AC-14).
 *
 * <p>The service is a Mockito mock, so these tests cover the adapter and the validation layer that
 * runs before it: JSON (de)serialisation with snake_case names, {@code @Valid} rejections, and the
 * problem+json rendering of every error. Persistence, allocation and semantic checks are pinned by
 * {@link UrlWriteServiceTest}; the full stack by {@code CreateShortUrlIT}. "Nothing persisted" is
 * asserted as {@code verifyNoInteractions(service)}: if the service is never called, no row can
 * have been written.
 */
class UrlWriteControllerTest {

  /** A valid long URL used in every request body. */
  private static final String LONG_URL = "https://example.com/some/path";

  /** The expiry the service mock reports; far in the future so it is unambiguous. */
  private static final Instant EXPIRES_AT = Instant.parse("2030-01-01T00:00:00Z");

  /** Contract values of {@link GlobalExceptionHandler} (package-private there). */
  private static final String PROBLEM_TYPE_BASE = "https://example.com/problems/";

  /** The fixed {@code detail} of every unreadable-body 400; never echoes Jackson's message. */
  private static final String DETAIL_MALFORMED_REQUEST =
      "Request body is missing, malformed or contains a value of the wrong type";

  /** The fixed, non-revealing {@code detail} every 500 carries (AC-16). */
  private static final String DETAIL_INTERNAL_ERROR =
      "An unexpected error occurred while processing the request";

  /** Stubbed per test to return a response or throw one of the domain exceptions. */
  private final UrlWriteService service = mock(UrlWriteService.class);

  /**
   * Standalone MockMvc over the controller under test plus the real exception handler, so error
   * responses go through the same {@code problem+json} rendering as in production.
   */
  private final MockMvcTester mvc =
      MockMvcTester.of(
          List.of(new UrlWriteController(service)),
          builder -> builder.setControllerAdvice(new GlobalExceptionHandler()).build());

  // --- 201 (AC-1) ------------------------------------------------------------------------------

  /**
   * A minimal body yields 201 with the five snake_case fields (camelCase absent), {@code
   * expires_at} rendered as JSON null, {@code Location} = short URL, and the service receives a
   * request with only {@code long_url} set.
   */
  @Test
  void createsShortUrlAndReturns201WithSnakeCasePayload() {
    when(service.create(any()))
        .thenReturn(
            new CreateUrlResponse(
                "http://localhost:8080/100001", "100001", LONG_URL, null, "redis"));

    MvcTestResult result = post("{\"long_url\":\"" + LONG_URL + "\"}");

    assertThat(result)
        .hasStatus(HttpStatus.CREATED)
        .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.short_url")
        .isEqualTo("http://localhost:8080/100001");
    assertThat(result).bodyJson().extractingPath("$.short_code").isEqualTo("100001");
    assertThat(result).bodyJson().extractingPath("$.long_url").isEqualTo(LONG_URL);
    assertThat(result).bodyJson().extractingPath("$.expires_at").isNull();
    assertThat(result).bodyJson().extractingPath("$.code_source").isEqualTo("redis");
    assertThat(result).bodyJson().doesNotHavePath("$.shortUrl");
    assertThat(result).headers().hasValue(HttpHeaders.LOCATION, "http://localhost:8080/100001");

    ArgumentCaptor<CreateUrlRequest> captor = ArgumentCaptor.forClass(CreateUrlRequest.class);
    verify(service).create(captor.capture());
    assertThat(captor.getValue()).isEqualTo(new CreateUrlRequest(LONG_URL, null, null));
  }

  /**
   * {@code custom_alias} and an offset date-time {@code expiration_date} are deserialised and
   * passed to the service unchanged (the offset is preserved as an instant), and the returned
   * expiry is rendered as an ISO-8601 UTC {@code expires_at}.
   */
  @Test
  void passesAliasAndExpiryThroughAndRendersExpiresAt() {
    when(service.create(any()))
        .thenReturn(
            new CreateUrlResponse(
                "http://localhost:8080/promo2024", "promo2024", LONG_URL, EXPIRES_AT, "redis"));

    MvcTestResult result =
        post(
            "{\"long_url\":\""
                + LONG_URL
                + "\",\"custom_alias\":\"promo2024\","
                + "\"expiration_date\":\"2030-01-01T01:00:00+01:00\"}");

    assertThat(result).hasStatus(HttpStatus.CREATED);
    assertThat(result).bodyJson().extractingPath("$.short_code").isEqualTo("promo2024");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.expires_at")
        .asString()
        .startsWith("2030-01-01T00:00:00");

    ArgumentCaptor<CreateUrlRequest> captor = ArgumentCaptor.forClass(CreateUrlRequest.class);
    verify(service).create(captor.capture());
    assertThat(captor.getValue().customAlias()).isEqualTo("promo2024");
    assertThat(captor.getValue().expirationDate().toInstant()).isEqualTo(EXPIRES_AT);
  }

  /** The {@code db_sequence} wire value reported by the service reaches the client unchanged. */
  @Test
  void dbSequenceCodeSourceIsRenderedAsIs() {
    when(service.create(any()))
        .thenReturn(
            new CreateUrlResponse(
                "http://localhost:8080/1Xy9zQ", "1Xy9zQ", LONG_URL, null, "db_sequence"));

    MvcTestResult result = post("{\"long_url\":\"" + LONG_URL + "\"}");

    assertThat(result).hasStatus(HttpStatus.CREATED);
    assertThat(result).bodyJson().extractingPath("$.code_source").isEqualTo("db_sequence");
  }

  // --- 400: validation failures never reach the service (nothing persisted) -------------------

  /**
   * A {@code long_url} that is non-http(s), empty, blank, not a URL or missing altogether is a 400
   * {@code validation-error} whose detail names {@code long_url}; the service is never invoked.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"long_url\":\"ftp://example.com/file\"}",
        "{\"long_url\":\"\"}",
        "{\"long_url\":\"   \"}",
        "{\"long_url\":\"not a url\"}",
        "{}"
      })
  void invalidLongUrlIs400ValidationProblemAndNothingIsPersisted(String body) {
    MvcTestResult result = post(body);

    assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error");
    assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("long_url");
    verifyNoInteractions(service);
  }

  /** A {@code long_url} longer than 2048 characters is a 400 {@code validation-error}. */
  @Test
  void tooLongLongUrlIs400AndNothingIsPersisted() {
    MvcTestResult result = post("{\"long_url\":\"https://example.com/" + "a".repeat(2048) + "\"}");

    assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error");
    assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("long_url");
    verifyNoInteractions(service);
  }

  /**
   * An alias that is too short, contains a hyphen or a space, or exceeds 32 characters is a 400
   * {@code validation-error} whose detail names {@code custom_alias}; the service is never invoked.
   */
  @ParameterizedTest
  @ValueSource(strings = {"ab", "bad-alias", "with space", "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q"})
  void invalidCustomAliasIs400AndNothingIsPersisted(String alias) {
    MvcTestResult result =
        post("{\"long_url\":\"" + LONG_URL + "\",\"custom_alias\":\"" + alias + "\"}");

    assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error");
    assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("custom_alias");
    verifyNoInteractions(service);
  }

  /** A well-formed but past {@code expiration_date} fails {@code @Future} with a 400. */
  @Test
  void pastExpirationDateIs400AndNothingIsPersisted() {
    MvcTestResult result =
        post("{\"long_url\":\"" + LONG_URL + "\",\"expiration_date\":\"2001-01-01T00:00:00Z\"}");

    assertProblem(result, HttpStatus.BAD_REQUEST, "validation-error");
    assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("expiration_date");
    verifyNoInteractions(service);
  }

  /**
   * Bodies Jackson cannot bind (unparseable date, invalid JSON, empty body, an array, a field of
   * the wrong type) are a 400 {@code malformed-request} with the fixed detail, so Jackson's own
   * message never leaks; the service is never invoked.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"long_url\":\"https://example.com\",\"expiration_date\":\"tomorrow\"}",
        "{\"long_url\":\"https://example.com\",\"expiration_date\":\"2030-13-45\"}",
        "{ this is not json",
        "",
        "[]",
        "{\"long_url\":[\"https://example.com\"]}"
      })
  void malformedBodyIs400MalformedRequestProblemAndNothingIsPersisted(String body) {
    MvcTestResult result = post(body);

    assertProblem(result, HttpStatus.BAD_REQUEST, "malformed-request");
    assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo(DETAIL_MALFORMED_REQUEST);
    verifyNoInteractions(service);
  }

  /**
   * A body that passes the DTO's regex but is rejected semantically by the service ({@link
   * InvalidUrlException}) is a 400 {@code invalid-url} whose detail is the service's message.
   */
  @Test
  void semanticRejectionFromTheServiceIs400InvalidUrlProblem() {
    when(service.create(any()))
        .thenThrow(new InvalidUrlException(UrlWriteService.DETAIL_INVALID_LONG_URL));

    // Passes the DTO's structural pattern but has no host: rejected by the service (AC-2 depth).
    MvcTestResult result = post("{\"long_url\":\"https:///no-host\"}");

    assertProblem(result, HttpStatus.BAD_REQUEST, "invalid-url");
    verify(service).create(new CreateUrlRequest("https:///no-host", null, null));
    assertThat(result)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo(UrlWriteService.DETAIL_INVALID_LONG_URL);
  }

  // --- 409 (AC-3) ------------------------------------------------------------------------------

  /**
   * {@link AliasAlreadyExistsException} from the service is a 409 problem+json with the {@code
   * alias-already-exists} type and a detail naming the alias.
   */
  @Test
  void takenAliasIs409ConflictProblem() {
    when(service.create(any())).thenThrow(new AliasAlreadyExistsException("promo2024"));

    MvcTestResult result =
        post("{\"long_url\":\"" + LONG_URL + "\",\"custom_alias\":\"promo2024\"}");

    assertProblem(result, HttpStatus.CONFLICT, "alias-already-exists");
    assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Alias already exists");
    assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("promo2024");
  }

  // --- 500 (AC-16) -----------------------------------------------------------------------------

  /**
   * Any other exception from the service becomes a 500 problem+json with the fixed generic detail;
   * the exception message, class name and package never appear anywhere in the body.
   */
  @Test
  void unexpectedServiceFailureIs500ProblemWithoutInternalDetails() {
    when(service.create(any())).thenThrow(new IllegalStateException("connection pool exhausted"));

    MvcTestResult result = post("{\"long_url\":\"" + LONG_URL + "\"}");

    assertProblem(result, HttpStatus.INTERNAL_SERVER_ERROR, "internal-error");
    assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo(DETAIL_INTERNAL_ERROR);
    assertThat(bodyOf(result))
        .doesNotContain("connection pool exhausted")
        .doesNotContain("IllegalStateException")
        .doesNotContain("com.example");
  }

  // --- request shape -------------------------------------------------------------------------

  /**
   * The endpoint consumes JSON only ({@code consumes} on the mapping): a {@code text/plain} body is
   * a 415 problem+json and the service is never invoked.
   */
  @Test
  void nonJsonContentTypeIs415ProblemAndNothingIsPersisted() {
    MvcTestResult result =
        mvc.post()
            .uri(UrlWriteController.PATH)
            .contentType(MediaType.TEXT_PLAIN)
            .content("long_url=https://example.com")
            .exchange();

    assertProblem(result, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type");
    verifyNoInteractions(service);
  }

  /** The write route accepts POST only; a GET is a 405 problem+json. */
  @Test
  void getOnTheWriteRouteIsMethodNotAllowed() {
    MvcTestResult result = mvc.get().uri(UrlWriteController.PATH).exchange();

    assertProblem(result, HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed");
  }

  // --- profile gating (AC-14) ------------------------------------------------------------------

  /**
   * Reflection check of the wiring contract: the class is a {@code @RestController} gated on
   * exactly {@code !read}, mapped to {@code /api/v1/urls} at class level, and {@code
   * createShortUrl} is a POST that adds no further path segment.
   */
  @Test
  void controllerIsGatedOnNotReadProfileAndMapsPostApiV1Urls() throws NoSuchMethodException {
    Profile profile = UrlWriteController.class.getAnnotation(Profile.class);
    assertThat(profile).isNotNull();
    assertThat(profile.value()).containsExactly("!read");
    assertThat(UrlWriteController.class.isAnnotationPresent(RestController.class)).isTrue();
    assertThat(UrlWriteController.class.getAnnotation(RequestMapping.class).path())
        .containsExactly("/api/v1/urls");
    PostMapping mapping =
        UrlWriteController.class
            .getMethod("createShortUrl", CreateUrlRequest.class)
            .getAnnotation(PostMapping.class);
    assertThat(mapping).isNotNull();
    assertThat(mapping.path()).isEmpty();
  }

  /**
   * Behavioural check of the gate with a minimal Spring context: the bean is missing under {@code
   * spring.profiles.active=read} and present under {@code write} and under no profile at all.
   */
  @Test
  void controllerIsAbsentUnderTheReadProfileAndPresentOtherwise() {
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withBean(UrlWriteService.class, () -> mock(UrlWriteService.class))
            .withUserConfiguration(UrlWriteController.class);

    runner
        .withPropertyValues("spring.profiles.active=read")
        .run(context -> assertThat(context).doesNotHaveBean(UrlWriteController.class));
    runner
        .withPropertyValues("spring.profiles.active=write")
        .run(context -> assertThat(context).hasSingleBean(UrlWriteController.class));
    runner.run(context -> assertThat(context).hasSingleBean(UrlWriteController.class));
  }

  // --- helpers ---------------------------------------------------------------------------------

  /**
   * Performs {@code POST /api/v1/urls} with an {@code application/json} body.
   *
   * @param body the raw JSON (or deliberately malformed) request body
   * @return the exchange result for AssertJ assertions
   */
  private MvcTestResult post(String body) {
    return mvc.post()
        .uri(UrlWriteController.PATH)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }

  /**
   * Asserts the RFC 9457 shape shared by every error response: status, {@code
   * application/problem+json}, the stable {@code type} URI, non-blank title and detail, {@code
   * instance} = the write route, and none of the fields that would leak internals.
   *
   * @param result the exchange result
   * @param status the expected HTTP status
   * @param typeSlug the last segment of the expected problem {@code type}
   */
  private static void assertProblem(MvcTestResult result, HttpStatus status, String typeSlug) {
    assertThat(result).hasStatus(status).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(status.value());
    assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(PROBLEM_TYPE_BASE + typeSlug);
    assertThat(result).bodyJson().extractingPath("$.title").asString().isNotBlank();
    assertThat(result).bodyJson().extractingPath("$.detail").asString().isNotBlank();
    assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo(UrlWriteController.PATH);
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
