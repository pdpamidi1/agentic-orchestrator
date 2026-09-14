/*
 * AnalyticsProperties.java — Typed binding of the analytics.* configuration namespace
 *
 * Layer: analytics.config. A leaf: depends on nothing inside the application (ArchitectureTest) and
 * is consumed by the recording (salt), outbox (poll interval, batch size, retry policy), kafka
 * (request timeout) and retention (days) packages of the click-analytics feature. The defaults
 * here duplicate the values documented in application.yml and docs/analytics.md (AMB-12, AMB-16,
 * AMB-17, AMB-18); AnalyticsPropertiesTest pins the two together.
 */
package com.example.shortener.analytics.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Click-analytics settings bound from the {@code analytics.*} namespace.
 *
 * <p><b>Responsibility.</b> Give every analytics tunable a typed, validated, defaulted home so a
 * misconfiguration (empty salt, zero batch, backoff cap below the base) fails at start-up rather
 * than on the first redirect or the first relay pass.
 *
 * <p><b>Invariants</b> (after successful binding): {@code salt} is non-blank and at least {@value
 * #MIN_SALT_LENGTH} characters; every numeric knob is within the bounds declared on its component;
 * {@code retry.maxBackoffMs >= retry.baseBackoffMs}. Bean Validation runs because of {@link
 * Validated}; a violation aborts context start-up.
 *
 * <p><b>Thread-safety and lifecycle.</b> Immutable record; one instance is created by Spring Boot's
 * constructor binding and exposed as a singleton bean through the nested {@link Registration}
 * configuration, which component scanning picks up automatically.
 *
 * <p><b>Security.</b> The salt is a secret: it is never logged and never given a real default in
 * code. {@code application.yml} reads it from {@code SHORTENER_ANALYTICS_IP_SALT} with a clearly
 * non-secret local-development fallback.
 *
 * @param salt secret mixed into the client-IP hash (AMB-12); no code default
 * @param outbox outbox relay polling settings (AMB-16)
 * @param retry publish retry / backoff policy (AMB-17)
 * @param kafka Kafka request timeout (AMB-17)
 * @param retention raw click-event retention (AMB-18)
 */
@Validated
@ConfigurationProperties(prefix = "analytics")
public record AnalyticsProperties(
    // analytics.salt / SHORTENER_ANALYTICS_IP_SALT. Required; secret.
    @NotBlank @Size(min = MIN_SALT_LENGTH) String salt,
    @Valid @DefaultValue Outbox outbox,
    @Valid @DefaultValue Retry retry,
    @Valid @DefaultValue Kafka kafka,
    @Valid @DefaultValue Retention retention) {

  /** Minimum salt length; shorter salts make the IP hash trivially brute-forceable. */
  public static final int MIN_SALT_LENGTH = 16;

  /** Default outbox poll interval in milliseconds (AMB-16). */
  public static final long DEFAULT_OUTBOX_POLL_INTERVAL_MS = 500L;

  /** Default number of events relayed per pass (AMB-16). */
  public static final int DEFAULT_OUTBOX_BATCH_SIZE = 100;

  /** Default number of publish attempts before an event is parked (AMB-17). */
  public static final int DEFAULT_RETRY_MAX_ATTEMPTS = 5;

  /** Default first backoff in milliseconds (AMB-17). */
  public static final long DEFAULT_RETRY_BASE_BACKOFF_MS = 100L;

  /** Default backoff cap in milliseconds (AMB-17). */
  public static final long DEFAULT_RETRY_MAX_BACKOFF_MS = 10_000L;

  /** Default jitter strategy (AMB-17). */
  public static final Jitter DEFAULT_RETRY_JITTER = Jitter.FULL;

  /** Default Kafka produce timeout in milliseconds (AMB-17). */
  public static final long DEFAULT_KAFKA_TIMEOUT_MS = 5_000L;

  /** Default raw-event retention in days (AMB-18). */
  public static final int DEFAULT_RETENTION_DAYS = 90;

  /** Jitter applied to the exponential backoff between publish attempts. */
  public enum Jitter {
    /** Deterministic backoff: {@code min(cap, base * 2^attempt)}. */
    NONE,
    /** Half deterministic, half random: {@code d/2 + random(0, d/2)}. */
    EQUAL,
    /** Fully random in {@code [0, min(cap, base * 2^attempt)]}; the AMB-17 default. */
    FULL
  }

  /**
   * Outbox relay polling.
   *
   * @param pollIntervalMs delay between relay passes, 50ms..60s (default 500)
   * @param batchSize events per pass, 1..10000 (default 100)
   */
  public record Outbox(
      @Min(50) @Max(60_000) @DefaultValue("500") long pollIntervalMs,
      @Min(1) @Max(10_000) @DefaultValue("100") int batchSize) {

    /** Poll interval as a {@link Duration}. */
    public Duration pollInterval() {
      return Duration.ofMillis(pollIntervalMs);
    }
  }

  /**
   * Publish retry policy: exponential backoff with jitter.
   *
   * @param maxAttempts total attempts including the first, 1..100 (default 5)
   * @param baseBackoffMs first backoff, at least 1ms (default 100)
   * @param maxBackoffMs backoff cap, at least {@code baseBackoffMs} (default 10000)
   * @param jitter jitter strategy (default {@link Jitter#FULL})
   */
  public record Retry(
      @Min(1) @Max(100) @DefaultValue("5") int maxAttempts,
      @Min(1) @DefaultValue("100") long baseBackoffMs,
      @Min(1) @DefaultValue("10000") long maxBackoffMs,
      @NotNull @DefaultValue("full") Jitter jitter) {

    /** Base backoff as a {@link Duration}. */
    public Duration baseBackoff() {
      return Duration.ofMillis(baseBackoffMs);
    }

    /** Backoff cap as a {@link Duration}. */
    public Duration maxBackoff() {
      return Duration.ofMillis(maxBackoffMs);
    }

    /**
     * Validation hook: the cap must not be below the base, otherwise the schedule is meaningless.
     */
    @AssertTrue(message = "max-backoff-ms must be >= base-backoff-ms")
    public boolean isBackoffRangeOrdered() {
      return maxBackoffMs >= baseBackoffMs;
    }
  }

  /**
   * Kafka producer settings owned by analytics (broker addresses stay under {@code spring.kafka}).
   *
   * @param timeoutMs upper bound for one produce request, 100ms..60s (default 5000)
   */
  public record Kafka(@Min(100) @Max(60_000) @DefaultValue("5000") long timeoutMs) {

    /** Request timeout as a {@link Duration}. */
    public Duration timeout() {
      return Duration.ofMillis(timeoutMs);
    }
  }

  /**
   * Raw click-event retention.
   *
   * @param days age after which raw events are purged, 1..3650 (default 90)
   */
  public record Retention(@Min(1) @Max(3650) @DefaultValue("90") int days) {

    /** Retention as a {@link Duration}. */
    public Duration period() {
      return Duration.ofDays(days);
    }
  }

  /**
   * Registers the record as a bound bean. Component scanning of {@code com.example.shortener} finds
   * this nested configuration, so the application class needs no change; registration is idempotent
   * if a later configuration also enables the same properties class.
   */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AnalyticsProperties.class)
  public static class Registration {}
}
