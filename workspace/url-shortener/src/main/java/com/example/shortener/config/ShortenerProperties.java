/*
 * ShortenerProperties.java — Typed binding of the shortener.* configuration namespace
 *
 * Layer: config. A leaf: depends on nothing inside the application (enforced by ArchitectureTest)
 * and is depended on by UrlWriteService (base-url), RedisBatchCounterSource (counter-batch-size),
 * RedisUrlCache (cache-ttl) and ShortCodeAllocator (counter-seed-offset). Every value can be
 * overridden by the environment variables listed in the README "Configuration" table; the
 * defaults here duplicate the local-development values in application.yml (AMB-9, AMB-11).
 */
package com.example.shortener.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Application settings bound from the {@code shortener.*} namespace.
 *
 * <p>This class is a configuration leaf: it depends on nothing inside the application.
 *
 * <p><b>Responsibility.</b> Give the four tunables a typed, validated, defaulted home so that
 * collaborators receive an {@code int}, a {@code Duration} or a {@code long} instead of parsing
 * strings, and so that a misconfiguration fails at start-up rather than on the first request.
 *
 * <p><b>Invariants</b> (after successful binding): {@code baseUrl} is non-blank and has no trailing
 * slash; {@code counterBatchSize >= 1}; {@code cacheTtl} is non-null (positivity is checked by
 * {@code RedisUrlCache}); {@code counterSeedOffset >= 0} (the stricter floor of {@code 62^2} is
 * checked by {@code ShortCodeAllocator}). Bean Validation runs because of the {@code @Validated}
 * annotation; a violation aborts context start-up, as {@code ShortenerPropertiesTest} asserts.
 *
 * <p><b>Thread-safety and lifecycle.</b> Immutable record; one instance is created by Spring Boot's
 * constructor binding and exposed as a singleton bean (registered through the application's
 * {@code @ConfigurationPropertiesScan} / {@code @EnableConfigurationProperties}).
 *
 * <p><b>Design choice.</b> A record with {@code @DefaultValue} keeps defaults next to the fields
 * they belong to; the {@code DEFAULT_*} constants restate the same values for tests and for other
 * code that needs them without a bound instance. The two must be kept in sync manually.
 *
 * @param baseUrl public base URL used to render short links (default {@code
 *     http://localhost:8080}); a trailing slash is stripped by {@link #baseUrl()}
 * @param counterBatchSize number of counter values reserved from Redis per batch (default 1000)
 * @param cacheTtl default TTL of cached short-code lookups (default 24h, AMB-9)
 * @param counterSeedOffset value added to the raw counter before encoding so the very first codes
 *     are neither trivially short nor guessable (AMB-11); the default {@code 62^5 = 916_132_832}
 *     yields base62 codes of at least six characters
 */
@Validated
@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(
    // shortener.base-url / SHORTENER_BASE_URL. Scheme + host (+ port) the public sees.
    @NotBlank @DefaultValue(DEFAULT_BASE_URL) String baseUrl,
    // shortener.counter-batch-size / SHORTENER_COUNTER_BATCH_SIZE. Trades Redis round trips
    // against the size of the gap lost on restart (up to batch - 1 values).
    @Min(1) @DefaultValue("1000") int counterBatchSize,
    // shortener.cache-ttl / SHORTENER_CACHE_TTL. Spring parses "24h", "15m", ISO-8601, etc.
    @NotNull @DefaultValue("24h") Duration cacheTtl,
    // shortener.counter-seed-offset / SHORTENER_COUNTER_SEED_OFFSET. 62^5 => six-char codes.
    @Min(0) @DefaultValue("916132832") long counterSeedOffset) {

  /**
   * Default public base URL, suitable for local development only; production deployments set {@code
   * SHORTENER_BASE_URL} to the public hostname of the read surface.
   */
  public static final String DEFAULT_BASE_URL = "http://localhost:8080";

  /**
   * Default size of a Redis counter batch: one {@code INCRBY} per 1000 codes per write instance, at
   * the cost of losing up to 999 unused values when an instance stops.
   */
  public static final int DEFAULT_COUNTER_BATCH_SIZE = 1000;

  /**
   * Default cache TTL (AMB-9): how long a never-expiring link stays in Redis, and the upper bound
   * for links with an {@code expires_at}. 24 hours bounds the staleness window in the absence of
   * any cache invalidation on delete/update (neither exists in this release).
   */
  public static final Duration DEFAULT_CACHE_TTL = Duration.ofHours(24);

  /**
   * Default counter seed offset (AMB-11): {@code 62^5}, the smallest value whose base62 encoding
   * has six characters, so generated codes never start shorter than six and never at {@code "1"}.
   */
  public static final long DEFAULT_COUNTER_SEED_OFFSET = 916_132_832L;

  /**
   * Canonical constructor; normalises the base URL by removing any trailing slashes.
   *
   * <p>Normalisation happens before the {@code @NotBlank} check is evaluated by the validator, so a
   * value consisting only of slashes fails validation as blank. {@code null} is passed through
   * untouched and left for {@code @NotBlank} to reject.
   */
  public ShortenerProperties {
    if (baseUrl != null) {
      baseUrl = stripTrailingSlashes(baseUrl);
    }
  }

  /**
   * Removes every trailing {@code '/'} so that {@code short_url} can be built as {@code baseUrl +
   * "/" + code} without producing a double slash.
   *
   * @param value the configured base URL, non-null
   * @return {@code value} without trailing slashes; may be empty when the input was only slashes
   */
  private static String stripTrailingSlashes(String value) {
    String result = value;
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
