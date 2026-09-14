/*
 * ProfileSeparationIT.java — read / write / default deployment surfaces (Spring profiles).
 *
 * Layer: test (integration). Pins AC-14: with spring.profiles.active=read only GET /{short_code}
 * is registered (POST answers 404 problem+json), with write only POST /api/v1/urls is registered
 * (GET answers 404 without Location), and the default profile serves both; and that a link written
 * by one surface is readable by the other because both share the same Postgres and Redis.
 * Technique: Spring Boot test on Testcontainers (inherited default-profile instance) plus two
 * extra application instances booted programmatically with SpringApplicationBuilder on random
 * ports against the same containers. Requires Docker; run with ./mvnw -Pit verify (failsafe).
 */
package com.example.shortener.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortener.UrlShortenerApplication;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Deployment surfaces (AC-14): an instance started with {@code spring.profiles.active=read}
 * registers only the redirect endpoint (POST answers 404), one started with {@code write} registers
 * only the creation endpoint (GET answers 404), and the default profile serves both.
 *
 * <p>The read and write instances are booted programmatically on a random port against the same
 * containers as the shared test context, so a link created by one surface is resolvable by the
 * other exactly as in production.
 *
 * <p>Fixture strategy: the inherited {@code @SpringBootTest} context is the "default profile"
 * instance. For the other two, {@link #boot(String)} starts a full {@link UrlShortenerApplication}
 * with command-line arguments (profile, {@code server.port=0}, the test base URL, batch size 1 and
 * the container coordinates from {@code containerProperties()}), and the try-with-resources block
 * closes that context at the end of the test so no second Tomcat or connection pool outlives it.
 * Test data is created on the shared instance and looked up via the shared repository.
 *
 * <p>Removing this class would leave the {@code @Profile("!write")} / {@code @Profile("!read")}
 * wiring of the two controllers unverified; a typo there would either register both surfaces
 * everywhere or none, and the unit tests (which instantiate controllers directly) would not notice.
 */
class ProfileSeparationIT extends AbstractIntegrationTest {

  /**
   * Given the shared default-profile instance, when a link is created and then requested on the
   * same origin, then POST answers 201 and GET answers 302 to the stored URL: both surfaces are up.
   */
  @Test
  void defaultProfileServesBothSurfaces() {
    String longUrl = uniqueLongUrl();

    HttpResponse<String> created = post(URLS_PATH, createRequest(longUrl, null, null));
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    String shortCode = (String) json(created.body()).get("short_code");

    HttpResponse<String> redirect = get("/" + shortCode);
    assertThat(redirect.statusCode()).isEqualTo(302);
    assertThat(header(redirect, "Location")).isEqualTo(longUrl);
  }

  /**
   * Given a code created on the shared instance and a second instance booted with the {@code read}
   * profile, when POST is sent to the read instance, then it answers a 404 problem (no write
   * controller); and when GET is sent for the code, then it answers 302 with {@code Location} and
   * {@code Cache-Control: private} (AC-14).
   */
  @Test
  void readProfileServesRedirectsButNotCreation() {
    String longUrl = uniqueLongUrl();
    String shortCode = createShortCode(longUrl);

    try (ConfigurableApplicationContext readInstance = boot("read")) {
      String origin = originOf(readInstance);

      HttpResponse<String> created =
          post(origin, URLS_PATH, createRequest(uniqueLongUrl(), null, null));
      assertProblem(created, 404);

      HttpResponse<String> redirect = get(origin, "/" + shortCode);
      assertThat(redirect.statusCode()).isEqualTo(302);
      assertThat(header(redirect, "Location")).isEqualTo(longUrl);
      assertThat(header(redirect, "Cache-Control")).isEqualTo("private");
    }
  }

  /**
   * Given a code created on the shared instance and a second instance booted with the {@code write}
   * profile, when GET is sent for the code, then it answers a 404 problem without a {@code
   * Location} header (no redirect controller); and when POST with alias {@code writeOnlyIT} is
   * sent, then it answers 201 and the row is visible through the shared repository (AC-14).
   */
  @Test
  void writeProfileServesCreationButNotRedirects() {
    String longUrl = uniqueLongUrl();
    String shortCode = createShortCode(longUrl);

    try (ConfigurableApplicationContext writeInstance = boot("write")) {
      String origin = originOf(writeInstance);

      HttpResponse<String> redirect = get(origin, "/" + shortCode);
      assertProblem(redirect, 404);
      assertThat(header(redirect, "Location")).isNull();

      HttpResponse<String> created =
          post(origin, URLS_PATH, createRequest(uniqueLongUrl(), "writeOnlyIT", null));
      assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
      assertThat(json(created.body()).get("short_code")).isEqualTo("writeOnlyIT");
      assertThat(repository.findByShortCode("writeOnlyIT")).isPresent();
    }
  }

  /**
   * Boots a second application instance with the given profile on a random port. Command-line
   * arguments are used because they outrank {@code application*.yml}; the container coordinates
   * come from the same source as the shared context so both instances share Postgres and Redis.
   *
   * @param profile {@code "read"} or {@code "write"}
   * @return the running context; close it to stop the embedded server
   */
  private static ConfigurableApplicationContext boot(String profile) {
    List<String> args = new ArrayList<>();
    args.add("--spring.profiles.active=" + profile);
    args.add("--server.port=0");
    args.add("--shortener.base-url=" + BASE_URL);
    args.add("--shortener.counter-batch-size=1");
    containerProperties().forEach((key, value) -> args.add("--" + key + "=" + value));
    return new SpringApplicationBuilder(UrlShortenerApplication.class)
        .run(args.toArray(String[]::new));
  }

  /**
   * Origin of a programmatically booted instance, read from its {@code local.server.port}.
   *
   * @param context a context started with {@code server.port=0}
   * @return {@code http://localhost:<port>}
   */
  private static String originOf(ConfigurableApplicationContext context) {
    return "http://localhost:"
        + context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
  }
}
