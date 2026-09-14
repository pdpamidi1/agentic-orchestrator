/*
 * AbstractIntegrationTest.java — shared fixture of every *IT: real Postgres + Redis, booted app,
 * HTTP and JSON helpers.
 *
 * Layer: test (integration). Holds no tests itself; it provides the Testcontainers PostgreSQL 17
 * and Redis 7 containers (started once per JVM), the @SpringBootTest boot of the whole application
 * on a random port wired to them via @DynamicPropertySource, per-test state reset, a JDK HttpClient
 * that never follows redirects, JSON/YAML parsing into maps, request-body builders and the shared
 * RFC 9457 problem assertion (AC-16). Subclasses cover AC-1..AC-15. Technique: Spring Boot test +
 * Testcontainers; requires Docker. Run only by failsafe under ./mvnw -Pit verify (surefire's
 * ./mvnw test excludes *IT and never touches this class).
 */
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
 *
 * <p>Fixture strategy in detail: all subclasses share the same {@code @SpringBootTest} annotation
 * and therefore the same cached application context (default profile, both HTTP surfaces), the same
 * two containers and the same Redis counter key; {@link #resetState()} empties the {@code urls}
 * table and the URL cache before every test but deliberately keeps the counter, so codes stay
 * unique across the whole run. Test data is created through the public API ({@link
 * #createShortCode(String)}) or, for states the API refuses such as expired rows, directly through
 * the autowired {@link #repository}.
 *
 * <p>Removing this class would remove the only Docker-backed fixture: every {@code *IT} extends it,
 * and the {@code -Pit} profile would then run no test at all.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "shortener.base-url=" + AbstractIntegrationTest.BASE_URL,
      "shortener.counter-batch-size=1"
    })
public abstract class AbstractIntegrationTest {

  /**
   * Public base URL configured for the tests; {@code short_url} must start with it. It differs on
   * purpose from the origin the tests talk to ({@code http://localhost:<random port>}), which
   * proves that {@code short_url} is built from configuration and not from the request's Host.
   */
  public static final String BASE_URL = "http://short.test";

  /** Route of the write endpoint (operationId {@code createShortUrl}). */
  protected static final String URLS_PATH = "/api/v1/urls";

  /** Media type of every error response. */
  protected static final String PROBLEM_JSON = "application/problem+json";

  /** Same major version as the README's local setup and the pom's {@code jdbc:tc:} URL. */
  static final String POSTGRES_IMAGE = "postgres:17-alpine";

  /** Same major version as the README's local setup. */
  static final String REDIS_IMAGE = "redis:7-alpine";

  /** Redis' port inside the container; the host side is a random mapped port. */
  static final int REDIS_PORT = 6379;

  /**
   * Shared PostgreSQL container (database, user and password are Testcontainers defaults). Never
   * closed explicitly: it lives for the JVM and is reaped by Testcontainers' Ryuk afterwards.
   */
  @SuppressWarnings("resource")
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE));

  /**
   * Shared Redis container, a plain {@link GenericContainer} exposing {@link #REDIS_PORT}. {@code
   * CacheAndFallbackIT} pauses and unpauses this very container to simulate an outage.
   */
  @SuppressWarnings({"rawtypes", "unchecked", "resource"})
  static final GenericContainer REDIS =
      new GenericContainer(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(REDIS_PORT);

  // Started once when the class loads, before Spring reads containerProperties(); see class
  // Javadoc for why @Container / JUnit lifecycle is not used.
  static {
    POSTGRES.start();
    REDIS.start();
  }

  /**
   * JDK HTTP client shared by all tests: {@code Redirect.NEVER} so a 302 is returned to the test
   * instead of being followed to example.com; 10 s connect timeout as a local-only safety net.
   */
  private static final HttpClient HTTP =
      HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NEVER)
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  /**
   * Per-request timeout. Generous because during the Redis outage test every Redis command first
   * runs into the application's 2 s client timeout before the request completes.
   */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

  /** Monotonic counter behind {@link #uniqueLongUrl()}. */
  private static final AtomicLong SEQUENCE = new AtomicLong();

  /** Direct access to the {@code urls} table for seeding rows and verifying persistence. */
  @Autowired protected UrlMappingRepository repository;

  /** The application's own Redis template: inspects cache keys and TTLs, probes connectivity. */
  @Autowired protected StringRedisTemplate redis;

  @Autowired private Environment environment;

  /**
   * Points the booted application at the two containers. Registered as suppliers so the values are
   * read after the static initialiser has started the containers.
   *
   * @param registry Spring's dynamic property registry for this test context
   */
  @DynamicPropertySource
  static void containerProperties(DynamicPropertyRegistry registry) {
    containerProperties().forEach((key, value) -> registry.add(key, () -> value));
  }

  /**
   * Spring properties pointing at the shared containers; also used to boot additional application
   * instances with other profiles ({@code ProfileSeparationIT}).
   *
   * @return datasource URL/credentials/driver and Redis host/mapped port, in insertion order
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

  /**
   * Every test starts from an empty {@code urls} table and an empty URL cache. The Redis counter
   * key is left alone so generated codes never repeat within the JVM.
   */
  @BeforeEach
  void resetState() {
    repository.deleteAllInBatch();
    clearUrlCache();
  }

  /**
   * Removes every cached mapping but leaves the counter key untouched. A Redis failure is
   * swallowed: when Redis is down there is nothing to clear, and the tests that care assert on it.
   */
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

  /**
   * GETs a path on the shared application instance.
   *
   * @param path request path starting with {@code /}
   * @return the raw response (redirects are not followed)
   */
  protected HttpResponse<String> get(String path) {
    return get(origin(), path);
  }

  /**
   * POSTs a JSON body to a path on the shared application instance.
   *
   * @param path request path starting with {@code /}
   * @param jsonBody the raw request body
   * @return the raw response
   */
  protected HttpResponse<String> post(String path, String jsonBody) {
    return post(origin(), path, jsonBody);
  }

  /**
   * GETs a path on an arbitrary origin (used for the extra read/write instances).
   *
   * @param origin scheme, host and port, without trailing slash
   * @param path request path starting with {@code /}
   * @return the raw response (redirects are not followed)
   */
  protected static HttpResponse<String> get(String origin, String path) {
    return send(HttpRequest.newBuilder(URI.create(origin + path)).GET());
  }

  /**
   * POSTs a JSON body to an arbitrary origin, accepting both {@code application/json} and {@code
   * application/problem+json} so error bodies are negotiable.
   *
   * @param origin scheme, host and port, without trailing slash
   * @param path request path starting with {@code /}
   * @param jsonBody the raw request body
   * @return the raw response
   */
  protected static HttpResponse<String> post(String origin, String path, String jsonBody) {
    return send(
        HttpRequest.newBuilder(URI.create(origin + path))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, application/problem+json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody)));
  }

  /**
   * Sends a request with {@link #REQUEST_TIMEOUT}, turning checked failures into unchecked ones so
   * tests stay free of {@code throws} clauses.
   *
   * @param request the request builder, completed here with the timeout
   * @return the response with the body as a string
   */
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

  /**
   * The {@code Content-Type} header, or an empty string when absent (e.g. on a 302).
   *
   * @param response any response
   * @return the header value or {@code ""}
   */
  protected static String contentType(HttpResponse<?> response) {
    return response.headers().firstValue("Content-Type").orElse("");
  }

  /**
   * First value of a response header, or {@code null} when absent (so tests can assert that {@code
   * Location} is missing on error responses).
   *
   * @param response any response
   * @param name header name, case-insensitive
   * @return the value or {@code null}
   */
  protected static String header(HttpResponse<?> response, String name) {
    return response.headers().firstValue(name).orElse(null);
  }

  // ---------------------------------------------------------------------------------------------
  // JSON
  // ---------------------------------------------------------------------------------------------

  /**
   * Parses a JSON (or YAML) document into a map. SnakeYAML is used for both because JSON is a
   * subset of YAML and {@code OpenApiContractIT} needs the YAML form of the live API document.
   *
   * @param document the response body
   * @return the top-level object; fails the test when the document is not an object
   */
  @SuppressWarnings("unchecked")
  protected static Map<String, Object> json(String document) {
    Object parsed = new Yaml().load(document);
    assertThat(parsed).as("document is a JSON object: %s", document).isInstanceOf(Map.class);
    return (Map<String, Object>) parsed;
  }

  /**
   * Renders a {@code createShortUrl} request body; {@code null} fields are omitted.
   *
   * @param longUrl value of {@code long_url} (rendered as JSON {@code null} when {@code null})
   * @param customAlias optional {@code custom_alias}
   * @param expirationDate optional {@code expiration_date}, already formatted as text
   * @return the JSON object as a string
   */
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

  /**
   * Renders a JSON string literal (escaping backslashes and quotes) or the literal {@code null}.
   *
   * @param value the raw value
   * @return the JSON token
   */
  private static String quote(String value) {
    if (value == null) {
      return "null";
    }
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  /**
   * A long URL that no other test in this JVM has used.
   *
   * @return {@code https://example.com/it/<n>?q=1&lang=en} with a fresh {@code n}
   */
  protected static String uniqueLongUrl() {
    return "https://example.com/it/" + SEQUENCE.incrementAndGet() + "?q=1&lang=en";
  }

  // ---------------------------------------------------------------------------------------------
  // Shared assertions
  // ---------------------------------------------------------------------------------------------

  /**
   * Creates a short link through the API and returns its {@code short_code}.
   *
   * @param longUrl the target URL
   * @return the generated code; the test fails if the API does not answer 201
   */
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
   * @param response the response to check
   * @param expectedStatus the HTTP status the problem must carry, both on the wire and in the body
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
