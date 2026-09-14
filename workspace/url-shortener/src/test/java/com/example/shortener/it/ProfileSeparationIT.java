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
 */
class ProfileSeparationIT extends AbstractIntegrationTest {

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

  /** Boots a second application instance with the given profile on a random port. */
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

  private static String originOf(ConfigurableApplicationContext context) {
    return "http://localhost:"
        + context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
  }
}
