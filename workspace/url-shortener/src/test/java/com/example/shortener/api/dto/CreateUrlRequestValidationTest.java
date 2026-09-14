/*
 * CreateUrlRequestValidationTest.java — validation and JSON binding rules of CreateUrlRequest.
 *
 * Layer: test (unit). Pins the structural rules of the createShortUrl request body: long_url must
 * be a non-blank absolute http/https URL of at most 2048 characters (AC-2), custom_alias must
 * match [0-9a-zA-Z]{3,32} (AC-5) and expiration_date must be a future ISO-8601 date-time carrying
 * an offset (AC-6), plus the snake_case JSON property names of the contract. Technique: plain
 * JUnit 5 (incl. @ParameterizedTest) against a bootstrapped Bean Validation ValidatorFactory and a
 * bare Jackson 3 JsonMapper; no Spring context, no containers. Run with ./mvnw test (surefire
 * picks up every *Test class).
 */
package com.example.shortener.api.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bean Validation and JSON binding rules of {@link CreateUrlRequest} (AC-2, AC-5, AC-6), verified
 * without a Spring context.
 *
 * <p>Fixture strategy: one {@code ValidatorFactory} is built in {@link #openValidator()} and shared
 * by every test of the class (building it is the expensive part), then closed in {@link
 * #closeValidator()}. Each test instance owns a default {@link JsonMapper}. Test data is built
 * inline from the constants below; the time-relative instants are computed once at class load, with
 * a margin of a day or more, so {@code @Future} is evaluated against the real clock without
 * flakiness.
 *
 * <p>Removing this class would leave the constraint annotations and {@code @JsonProperty} names on
 * the request record unguarded at unit level: a dropped {@code @Pattern}, a changed alias length or
 * a renamed JSON property would only surface through {@code GlobalExceptionHandlerTest} (which
 * covers a handful of cases) or the Docker-bound {@code CreateShortUrlIT}.
 */
class CreateUrlRequestValidationTest {

  /** A representative valid {@code long_url}: https scheme, host, path and query string. */
  private static final String VALID_URL = "https://example.com/some/path?q=1";

  /** An expiry safely in the future (30 days) so {@code @Future} passes for the whole run. */
  private static final OffsetDateTime FUTURE = OffsetDateTime.now(ZoneOffset.UTC).plusDays(30);

  /** An expiry safely in the past (1 day) so {@code @Future} fails deterministically. */
  private static final OffsetDateTime PAST = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);

  /** Shared per class: opened in {@link #openValidator()}, closed in {@link #closeValidator()}. */
  private static jakarta.validation.ValidatorFactory factory;

  /** Validator obtained from {@link #factory}; thread-safe, so one instance serves all tests. */
  private static Validator validator;

  /** Default Jackson 3 mapper, mirroring what Spring MVC uses to read the request body. */
  private final JsonMapper json = JsonMapper.builder().build();

  /** Bootstraps the default Bean Validation provider (Hibernate Validator) once for the class. */
  @BeforeAll
  static void openValidator() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  /** Releases the validator factory so the provider's resources do not leak across test classes. */
  @AfterAll
  static void closeValidator() {
    factory.close();
  }

  // --- valid input -----------------------------------------------------------------------------

  /** Given only a valid {@code long_url}, when validated, then there are no violations. */
  @Test
  void minimalValidRequestHasNoViolations() {
    assertThat(validate(new CreateUrlRequest(VALID_URL, null, null))).isEmpty();
  }

  /**
   * Given a plain http URL, a well-formed alias and a future expiry, when validated, then there are
   * no violations (http is as acceptable as https).
   */
  @Test
  void fullValidRequestHasNoViolations() {
    assertThat(validate(new CreateUrlRequest("http://example.org", "promo2024", FUTURE))).isEmpty();
  }

  /**
   * Given aliases of exactly 3, 6 and 32 alphanumeric characters (both length bounds inclusive),
   * when validated, then each is accepted.
   *
   * @param alias the boundary alias under test
   */
  @ParameterizedTest
  @ValueSource(strings = {"abc", "ABC123", "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6"})
  void aliasBoundariesThreeAndThirtyTwoCharactersAreAccepted(String alias) {
    assertThat(alias.length()).isBetween(3, 32);
    assertThat(validate(new CreateUrlRequest(VALID_URL, alias, null))).isEmpty();
  }

  /**
   * Given a {@code long_url} of exactly {@link CreateUrlRequest#MAX_LONG_URL_LENGTH} (2048)
   * characters, when validated, then it is accepted: the limit is inclusive.
   */
  @Test
  void longUrlOfExactlyMaxLengthIsAccepted() {
    String url = padUrl(CreateUrlRequest.MAX_LONG_URL_LENGTH);
    assertThat(url).hasSize(2048);
    assertThat(validate(new CreateUrlRequest(url, null, null))).isEmpty();
  }

  // --- long_url (AC-2) -------------------------------------------------------------------------

  /**
   * Given an ftp URL, a mailto URI or a bare host without scheme, when validated, then exactly the
   * {@code longUrl} property is violated (AC-2).
   *
   * @param url the rejected value
   */
  @ParameterizedTest
  @ValueSource(strings = {"ftp://example.com/file", "mailto:someone@example.com", "example.com"})
  void nonHttpSchemesAreRejected(String url) {
    assertThat(violatedProperties(new CreateUrlRequest(url, null, null)))
        .containsExactly("longUrl");
  }

  /**
   * Given an empty or whitespace-only {@code long_url}, when validated, then only {@code longUrl}
   * is reported (the {@code @NotBlank} and {@code @Pattern} violations collapse to one property).
   *
   * @param url the blank value
   */
  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void blankLongUrlIsRejected(String url) {
    assertThat(violatedProperties(new CreateUrlRequest(url, null, null)))
        .containsExactly("longUrl");
  }

  /** Given a {@code null} {@code long_url}, when validated, then {@code longUrl} is violated. */
  @Test
  void nullLongUrlIsRejected() {
    assertThat(violatedProperties(new CreateUrlRequest(null, null, null)))
        .containsExactly("longUrl");
  }

  /**
   * Given an otherwise valid URL of 2049 characters, when validated, then there is exactly one
   * violation, on {@code longUrl}, whose message names the 2048 limit (only {@code @Size} fails).
   */
  @Test
  void longUrlLongerThan2048CharactersIsRejected() {
    String url = padUrl(CreateUrlRequest.MAX_LONG_URL_LENGTH + 1);
    assertThat(url).hasSize(2049);
    Set<ConstraintViolation<CreateUrlRequest>> violations =
        validate(new CreateUrlRequest(url, null, null));
    assertThat(violations).hasSize(1);
    ConstraintViolation<CreateUrlRequest> violation = violations.iterator().next();
    assertThat(violation.getPropertyPath()).hasToString("longUrl");
    assertThat(violation.getMessage()).contains("2048");
  }

  // --- custom_alias (AC-5) ---------------------------------------------------------------------

  /**
   * Given an alias containing a hyphen, underscore, space, non-ASCII letter, dot or slash, when
   * validated, then exactly {@code customAlias} is violated (AC-5).
   *
   * @param alias the rejected alias
   */
  @ParameterizedTest
  @ValueSource(
      strings = {"bad-alias", "under_score", "with space", "ünïcode", "dots.here", "a/b/c"})
  void aliasWithCharactersOutsideAlphanumericIsRejected(String alias) {
    assertThat(violatedProperties(new CreateUrlRequest(VALID_URL, alias, null)))
        .containsExactly("customAlias");
  }

  /** Given a two-character alias (one below the minimum), when validated, then it is rejected. */
  @Test
  void aliasOfLengthTwoIsRejected() {
    assertThat(violatedProperties(new CreateUrlRequest(VALID_URL, "ab", null)))
        .containsExactly("customAlias");
  }

  /** Given a 33-character alias (one above the maximum), when validated, then it is rejected. */
  @Test
  void aliasOfLengthThirtyThreeIsRejected() {
    String alias = "a".repeat(33);
    assertThat(violatedProperties(new CreateUrlRequest(VALID_URL, alias, null)))
        .containsExactly("customAlias");
  }

  /**
   * Given an empty-string alias (present but blank, unlike {@code null} which means "absent"), when
   * validated, then it is rejected.
   */
  @Test
  void emptyAliasIsRejected() {
    assertThat(violatedProperties(new CreateUrlRequest(VALID_URL, "", null)))
        .containsExactly("customAlias");
  }

  // --- expiration_date (AC-6) ------------------------------------------------------------------

  /**
   * Given an {@code expiration_date} one day in the past, when validated, then exactly {@code
   * expirationDate} is violated (AC-6).
   */
  @Test
  void pastExpirationDateIsRejected() {
    assertThat(violatedProperties(new CreateUrlRequest(VALID_URL, null, PAST)))
        .containsExactly("expirationDate");
  }

  /**
   * Given a JSON body whose {@code expiration_date} is the word "tomorrow", when read with Jackson,
   * then binding fails with a {@link JacksonException} before validation could run (AC-6; the
   * handler turns this into a 400 "malformed request").
   */
  @Test
  void malformedExpirationDateFailsJsonBinding() {
    String body = "{\"long_url\":\"https://example.com\",\"expiration_date\":\"tomorrow\"}";
    assertThatThrownBy(() -> json.readValue(body, CreateUrlRequest.class))
        .isInstanceOf(JacksonException.class);
  }

  /**
   * Given a date-only value without time and offset, when read as {@link OffsetDateTime}, then
   * binding fails: the contract requires a full RFC 3339 date-time.
   */
  @Test
  void dateWithoutOffsetFailsJsonBinding() {
    String body = "{\"long_url\":\"https://example.com\",\"expiration_date\":\"2030-01-01\"}";
    assertThatThrownBy(() -> json.readValue(body, CreateUrlRequest.class))
        .isInstanceOf(JacksonException.class);
  }

  // --- JSON names ------------------------------------------------------------------------------

  /**
   * Given a body using the contract's snake_case names, when read with Jackson, then every field is
   * bound (the UTC offset is preserved) and the resulting record validates cleanly.
   */
  @Test
  void bindsSnakeCaseJsonNames() {
    String body =
        "{\"long_url\":\"https://example.com/x\","
            + "\"custom_alias\":\"promo2024\","
            + "\"expiration_date\":\"2030-01-01T00:00:00Z\"}";

    CreateUrlRequest request = json.readValue(body, CreateUrlRequest.class);

    assertThat(request.longUrl()).isEqualTo("https://example.com/x");
    assertThat(request.customAlias()).isEqualTo("promo2024");
    assertThat(request.expirationDate())
        .isEqualTo(OffsetDateTime.of(2030, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    assertThat(validate(request)).isEmpty();
  }

  /**
   * Given a body using the Java property name {@code longUrl} instead of {@code long_url}, when
   * read with unknown properties tolerated, then the field stays {@code null} and validation
   * reports {@code longUrl} missing: camelCase is not an accepted alias of the contract name.
   */
  @Test
  void camelCaseJsonNamesAreNotBound() {
    String body = "{\"longUrl\":\"https://example.com/x\"}";
    // Unknown properties are ignored by the default mapper; the snake_case field stays unset.
    CreateUrlRequest request =
        JsonMapper.builder()
            .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()
            .readValue(body, CreateUrlRequest.class);
    assertThat(request.longUrl()).isNull();
    assertThat(violatedProperties(request)).containsExactly("longUrl");
  }

  /**
   * Given a request violating all three fields at once, when validated, then all three properties
   * are reported in a single pass (the handler lists them together in one 400 detail).
   */
  @Test
  void aggregatesAllViolationsInOnePass() {
    CreateUrlRequest request = new CreateUrlRequest("ftp://x", "!!", PAST);
    assertThat(violatedProperties(request))
        .containsExactlyInAnyOrder("longUrl", "customAlias", "expirationDate");
  }

  // --- helpers ---------------------------------------------------------------------------------

  /**
   * Runs the shared validator over a request.
   *
   * @param request the record to validate
   * @return the raw constraint violations (empty when valid)
   */
  private static Set<ConstraintViolation<CreateUrlRequest>> validate(CreateUrlRequest request) {
    return validator.validate(request);
  }

  /**
   * Reduces the violations of a request to the set of violated Java property names, so tests can
   * assert on which field failed without depending on message texts.
   *
   * @param request the record to validate
   * @return distinct property paths such as {@code "longUrl"}
   */
  private static Set<String> violatedProperties(CreateUrlRequest request) {
    return validate(request).stream()
        .map(v -> v.getPropertyPath().toString())
        .collect(java.util.stream.Collectors.toSet());
  }

  /**
   * Builds a syntactically valid https URL of exactly the requested length by padding the path with
   * {@code 'a'}; used to probe the 2048-character boundary from both sides.
   *
   * @param totalLength the desired total length of the URL
   * @return a URL of {@code totalLength} characters
   */
  private static String padUrl(int totalLength) {
    String prefix = "https://example.com/";
    return prefix + "a".repeat(totalLength - prefix.length());
  }
}
