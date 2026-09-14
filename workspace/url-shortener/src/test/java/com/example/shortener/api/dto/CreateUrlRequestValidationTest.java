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
 */
class CreateUrlRequestValidationTest {

  private static final String VALID_URL = "https://example.com/some/path?q=1";
  private static final OffsetDateTime FUTURE = OffsetDateTime.now(ZoneOffset.UTC).plusDays(30);
  private static final OffsetDateTime PAST = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);

  private static jakarta.validation.ValidatorFactory factory;
  private static Validator validator;
  private final JsonMapper json = JsonMapper.builder().build();

  @BeforeAll
  static void openValidator() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void closeValidator() {
    factory.close();
  }

  // --- valid input -----------------------------------------------------------------------------

  @Test
  void minimalValidRequestHasNoViolations() {
    assertThat(validate(new CreateUrlRequest(VALID_URL, null, null))).isEmpty();
  }

  @Test
  void fullValidRequestHasNoViolations() {
    assertThat(validate(new CreateUrlRequest("http://example.org", "promo2024", FUTURE))).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"abc", "ABC123", "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6"})
  void aliasBoundariesThreeAndThirtyTwoCharactersAreAccepted(String alias) {
    assertThat(alias.length()).isBetween(3, 32);
    assertThat(validate(new CreateUrlRequest(VALID_URL, alias, null))).isEmpty();
  }

  @Test
  void longUrlOfExactlyMaxLengthIsAccepted() {
    String url = padUrl(CreateUrlRequest.MAX_LONG_URL_LENGTH);
    assertThat(url).hasSize(2048);
    assertThat(validate(new CreateUrlRequest(url, null, null))).isEmpty();
  }

  // --- long_url (AC-2) -------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {"ftp://example.com/file", "mailto:someone@example.com", "example.com"})
  void nonHttpSchemesAreRejected(String url) {
    assertThat(violatedProperties(new CreateUrlRequest(url, null, null)))
        .containsExactly("longUrl");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void blankLongUrlIsRejected(String url) {
    assertThat(violatedProperties(new CreateUrlRequest(url, null, null)))
        .containsExactly("longUrl");
  }

  @Test
  void nullLongUrlIsRejected() {
    assertThat(violatedProperties(new CreateUrlRequest(null, null, null)))
        .containsExactly("longUrl");
  }

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

  @ParameterizedTest
  @ValueSource(
      strings = {"bad-alias", "under_score", "with space", "ünïcode", "dots.here", "a/b/c"})
  void aliasWithCharactersOutsideAlphanumericIsRejected(String alias) {
    assertThat(violatedProperties(new CreateUrlRequest(VALID_URL, alias, null)))
        .containsExactly("customAlias");
  }

  @Test
  void aliasOfLengthTwoIsRejected() {
    assertThat(violatedProperties(new CreateUrlRequest(VALID_URL, "ab", null)))
        .containsExactly("customAlias");
  }

  @Test
  void aliasOfLengthThirtyThreeIsRejected() {
    String alias = "a".repeat(33);
    assertThat(violatedProperties(new CreateUrlRequest(VALID_URL, alias, null)))
        .containsExactly("customAlias");
  }

  @Test
  void emptyAliasIsRejected() {
    assertThat(violatedProperties(new CreateUrlRequest(VALID_URL, "", null)))
        .containsExactly("customAlias");
  }

  // --- expiration_date (AC-6) ------------------------------------------------------------------

  @Test
  void pastExpirationDateIsRejected() {
    assertThat(violatedProperties(new CreateUrlRequest(VALID_URL, null, PAST)))
        .containsExactly("expirationDate");
  }

  @Test
  void malformedExpirationDateFailsJsonBinding() {
    String body = "{\"long_url\":\"https://example.com\",\"expiration_date\":\"tomorrow\"}";
    assertThatThrownBy(() -> json.readValue(body, CreateUrlRequest.class))
        .isInstanceOf(JacksonException.class);
  }

  @Test
  void dateWithoutOffsetFailsJsonBinding() {
    String body = "{\"long_url\":\"https://example.com\",\"expiration_date\":\"2030-01-01\"}";
    assertThatThrownBy(() -> json.readValue(body, CreateUrlRequest.class))
        .isInstanceOf(JacksonException.class);
  }

  // --- JSON names ------------------------------------------------------------------------------

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

  @Test
  void aggregatesAllViolationsInOnePass() {
    CreateUrlRequest request = new CreateUrlRequest("ftp://x", "!!", PAST);
    assertThat(violatedProperties(request))
        .containsExactlyInAnyOrder("longUrl", "customAlias", "expirationDate");
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static Set<ConstraintViolation<CreateUrlRequest>> validate(CreateUrlRequest request) {
    return validator.validate(request);
  }

  private static Set<String> violatedProperties(CreateUrlRequest request) {
    return validate(request).stream()
        .map(v -> v.getPropertyPath().toString())
        .collect(java.util.stream.Collectors.toSet());
  }

  private static String padUrl(int totalLength) {
    String prefix = "https://example.com/";
    return prefix + "a".repeat(totalLength - prefix.length());
  }
}
