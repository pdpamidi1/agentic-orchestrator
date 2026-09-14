package com.example.shortener.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortener.domain.UrlMappingRepository;
import com.example.shortener.read.RedisUrlCache;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.yaml.snakeyaml.Yaml;

/**
 * Base class of every {@code *IT}: boots the whole application on a random port against a real
 * PostgreSQL and a real Redis started once per JVM with Testcontainers, wires their coordinates in
 * with {@link DynamicPropertySource} and lets Flyway migrate the schema on start-up.
 *
 * <p>The containers are started in a static initialiser (not with {@code @Container}) so that the
 * Spring test context, which is cached and shared across IT classes, never outlives them. Redis
 * counter batches are set to one so that every code allocation touches Redis and outages are
 * observable ({@code CacheAndFallbackIT}).
 *
 * <p>HTTP is driven with the JDK client and redirects disabled, so a {@code 302} is observed as
 * such. Bodies are parsed into plain maps; assertions never depend on application DTO classes.
 *
 * <p>These classes are only run by failsafe under {@code ./mvnw -Pit verify}; {@code ./mvnw test}
 * stays container-free.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "shortener.base-url=" + AbstractIntegrationTest.BASE_URL,
      "shortener.counter-batch-size=1"
    })
public abstract class AbstractIntegrationTest {

  /** Public base URL configured for the tests; {@code short_url} must start with it. */
  public static final String BASE_URL = "http://short.test";

  /** Route of the write endpoint (operationId {@code createShortUrl}). */
  protected static final String URLS_PATH = "/api/v1/urls";

  /** Media type of every error response. */
  protected static final String PROBLEM_JSON = "application/problem+json";

  static final String POSTGRES_IMAGE = "postgres:17-alpine";
  static final String REDIS_IMAGE = "redis:7-alpine";
  static final int REDIS_PORT = 6379;

  @SuppressWarnings("resource")
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE));

  @SuppressWarnings({"rawtypes", "unchecked", "resource"})
  static final GenericContainer REDIS =
      new GenericContainer(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(REDIS_PORT);

  static {
    POSTGRES.start();
    REDIS.start();
  }

  private static final HttpClient HTTP =
      HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NEVER)
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
  private static final AtomicLong SEQUENCE = new AtomicLong();

  @Autowired protected UrlMappingRepository repository;
  @Autowired protected StringRedisTemplate redis;
  @Autowired private Environment environment;

  @DynamicPropertySource
  static void containerProperties(DynamicPropertyRegistry registry) {
    containerProperties().forEach((key, value) -> registry.add(key, () -> value));
  }

  /**
   * Spring properties pointing at the shared containers; also used to boot additional application
   * instances with other profiles ({@code ProfileSeparationIT}).
   */
  protected static Map<String, String> containerProperties() {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("spring.datasource.url", POSTGRES.getJdbcUrl());
    properties.put("spring.datasource.username", POSTGRES.getUsername());
    properties.put("spring.datasource.password", POSTGRES.getPassword());
    properties.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
    properties.put("spring.data.redis.host", REDIS.getHost());
    properties.put("spring.data.redis.port", String.valueOf(REDIS.getMappedPort(REDIS_PORT)));
    return properties;
  }

  /** Every test starts from an empty {@code urls} table and an empty URL cache. */
  @BeforeEach
  void resetState() {
    repository.deleteAllInBatch();
    clearUrlCache();
  }

  /** Removes every cached mapping but leaves the counter key untouched. */
  protected void clearUrlCache() {
    try {
      Set<String> keys = redis.keys(RedisUrlCache.KEY_PREFIX + "*");
      if (keys != null && !keys.isEmpty()) {
        redis.delete(keys);
      }
    } catch (RuntimeException redisUnavailable) {
      // Nothing cached to clear when Redis is down; the tests that need it assert on it.
    }
  }

  // ---------------------------------------------------------------------------------------------
  // HTTP
  // ---------------------------------------------------------------------------------------------

  /** Port of the application booted by {@code @SpringBootTest}. */
  protected int port() {
    return environment.getRequiredProperty("local.server.port", Integer.class);
  }

  /** Origin of the application booted by {@code @SpringBootTest}. */
  protected String origin() {
    return "http://localhost:" + port();
  }

  protected HttpResponse<String> get(String path) {
    return get(origin(), path);
  }

  protected HttpResponse<String> post(String path, String jsonBody) {
    return post(origin(), path, jsonBody);
  }

  protected static HttpResponse<String> get(String origin, String path) {
    return send(HttpRequest.newBuilder(URI.create(origin + path)).GET());
  }

  protected static HttpResponse<String> post(String origin, String path, String jsonBody) {
    return send(
        HttpRequest.newBuilder(URI.create(origin + path))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, application/problem+json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody)));
  }

  private static HttpResponse<String> send(HttpRequest.Builder request) {
    try {
      return HTTP.send(
          request.timeout(REQUEST_TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for the response", e);
    }
  }

  protected static String contentType(HttpResponse<?> response) {
    return response.headers().firstValue("Content-Type").orElse("");
  }

  protected static String header(HttpResponse<?> response, String name) {
    return response.headers().firstValue(name).orElse(null);
  }

  // ---------------------------------------------------------------------------------------------
  // JSON
  // ---------------------------------------------------------------------------------------------

  /** Parses a JSON (or YAML) document into a map. */
  @SuppressWarnings("unchecked")
  protected static Map<String, Object> json(String document) {
    Object parsed = new Yaml().load(document);
    assertThat(parsed).as("document is a JSON object: %s", document).isInstanceOf(Map.class);
    return (Map<String, Object>) parsed;
  }

  /** Renders a {@code createShortUrl} request body; {@code null} fields are omitted. */
  protected static String createRequest(String longUrl, String customAlias, String expirationDate) {
    StringBuilder body = new StringBuilder("{\"long_url\":").append(quote(longUrl));
    if (customAlias != null) {
      body.append(",\"custom_alias\":").append(quote(customAlias));
    }
    if (expirationDate != null) {
      body.append(",\"expiration_date\":").append(quote(expirationDate));
    }
    return body.append('}').toString();
  }

  private static String quote(String value) {
    if (value == null) {
      return "null";
    }
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  /** A long URL that no other test in this JVM has used. */
  protected static String uniqueLongUrl() {
    return "https://example.com/it/" + SEQUENCE.incrementAndGet() + "?q=1&lang=en";
  }

  // ---------------------------------------------------------------------------------------------
  // Shared assertions
  // ---------------------------------------------------------------------------------------------

  /** Creates a short link through the API and returns its {@code short_code}. */
  protected String createShortCode(String longUrl) {
    HttpResponse<String> response = post(URLS_PATH, createRequest(longUrl, null, null));
    assertThat(response.statusCode()).as("createShortUrl: %s", response.body()).isEqualTo(201);
    return (String) json(response.body()).get("short_code");
  }

  /**
   * Asserts that a response is an RFC 9457 problem (AC-16): the expected status, {@code
   * application/problem+json}, the standard members, {@code instance} equal to the request path and
   * no leaked exception class names.
   *
   * @return the parsed problem body
   */
  protected static Map<String, Object> assertProblem(
      HttpResponse<String> response, int expectedStatus) {
    assertThat(response.statusCode()).as("status of %s", response.body()).isEqualTo(expectedStatus);
    assertThat(contentType(response)).startsWith(PROBLEM_JSON);
    Map<String, Object> problem = json(response.body());
    assertThat(problem).containsKeys("type", "title", "status", "detail", "instance");
    assertThat(problem.get("status")).isEqualTo(expectedStatus);
    assertThat(problem.get("instance")).isEqualTo(response.request().uri().getRawPath());
    assertThat(response.body()).doesNotContain("Exception").doesNotContain("\tat ");
    return problem;
  }
}
