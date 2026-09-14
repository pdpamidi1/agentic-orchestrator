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
    @NotBlank @DefaultValue(DEFAULT_BASE_URL) String baseUrl,
    @Min(1) @DefaultValue("1000") int counterBatchSize,
    @NotNull @DefaultValue("24h") Duration cacheTtl,
    @Min(0) @DefaultValue("916132832") long counterSeedOffset) {

  /** Default public base URL. */
  public static final String DEFAULT_BASE_URL = "http://localhost:8080";

  /** Default size of a Redis counter batch. */
  public static final int DEFAULT_COUNTER_BATCH_SIZE = 1000;

  /** Default cache TTL (AMB-9). */
  public static final Duration DEFAULT_CACHE_TTL = Duration.ofHours(24);

  /** Default counter seed offset (AMB-11): {@code 62^5}. */
  public static final long DEFAULT_COUNTER_SEED_OFFSET = 916_132_832L;

  /** Canonical constructor; normalises the base URL by removing any trailing slashes. */
  public ShortenerProperties {
    if (baseUrl != null) {
      baseUrl = stripTrailingSlashes(baseUrl);
    }
  }

  private static String stripTrailingSlashes(String value) {
    String result = value;
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
