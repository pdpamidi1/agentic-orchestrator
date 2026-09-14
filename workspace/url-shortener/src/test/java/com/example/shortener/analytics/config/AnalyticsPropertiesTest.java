/*
 * AnalyticsPropertiesTest.java — binding, defaults and validation of the analytics.* properties.
 *
 * Layer: test (unit). Pins the resolved defaults of AnalyticsProperties (AMB-12 salt from the
 * environment, AMB-16 outbox 500ms/100, AMB-17 retry 5 attempts / 100ms / 10s cap / full jitter and
 * 5s Kafka timeout, AMB-18 retention 90 days), that application.yml repeats the same values, that
 * explicit properties override them and that out-of-bounds values fail context start-up (AC-5,
 * AC-10). Technique: ApplicationContextRunner; no web server, no database, no Kafka, no containers.
 */
package com.example.shortener.analytics.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortener.analytics.config.AnalyticsProperties.Jitter;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifies defaults, YAML parity, overrides and validation bounds of {@link AnalyticsProperties}.
 */
class AnalyticsPropertiesTest {

  private static final String SALT = "analytics.salt=unit-test-salt-0123456789";

  /**
   * Registers only the nested {@code Registration} configuration; each test gets a fresh context.
   */
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(AnalyticsProperties.Registration.class);

  /** Given only a salt, when the context starts, then every other component carries its default. */
  @Test
  void codeDefaultsApplyWhenOnlySaltIsConfigured() {
    runner
        .withPropertyValues(SALT)
        .run(
            context -> {
              AnalyticsProperties props = context.getBean(AnalyticsProperties.class);
              assertThat(props.salt()).isEqualTo("unit-test-salt-0123456789");
              assertThat(props.outbox().pollIntervalMs()).isEqualTo(500L);
              assertThat(props.outbox().pollInterval()).isEqualTo(Duration.ofMillis(500));
              assertThat(props.outbox().batchSize()).isEqualTo(100);
              assertThat(props.retry().maxAttempts()).isEqualTo(5);
              assertThat(props.retry().baseBackoffMs()).isEqualTo(100L);
              assertThat(props.retry().maxBackoffMs()).isEqualTo(10_000L);
              assertThat(props.retry().baseBackoff()).isEqualTo(Duration.ofMillis(100));
              assertThat(props.retry().maxBackoff()).isEqualTo(Duration.ofSeconds(10));
              assertThat(props.retry().jitter()).isEqualTo(Jitter.FULL);
              assertThat(props.kafka().timeoutMs()).isEqualTo(5_000L);
              assertThat(props.kafka().timeout()).isEqualTo(Duration.ofSeconds(5));
              assertThat(props.retention().days()).isEqualTo(90);
              assertThat(props.retention().period()).isEqualTo(Duration.ofDays(90));
            });
  }

  /**
   * Given the real {@code application.yml}, when the context starts, then the bound values equal
   * the {@code DEFAULT_*} constants and the salt resolved to the documented non-secret local
   * fallback (i.e. no literal secret is committed and the env placeholder is wired).
   */
  @Test
  void applicationYamlDocumentsTheSameDefaults() {
    runner
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .run(
            context -> {
              AnalyticsProperties props = context.getBean(AnalyticsProperties.class);
              assertThat(props.salt())
                  .isEqualTo("local-dev-only-not-a-secret")
                  .hasSizeGreaterThanOrEqualTo(AnalyticsProperties.MIN_SALT_LENGTH);
              assertThat(props.outbox().pollIntervalMs())
                  .isEqualTo(AnalyticsProperties.DEFAULT_OUTBOX_POLL_INTERVAL_MS);
              assertThat(props.outbox().batchSize())
                  .isEqualTo(AnalyticsProperties.DEFAULT_OUTBOX_BATCH_SIZE);
              assertThat(props.retry().maxAttempts())
                  .isEqualTo(AnalyticsProperties.DEFAULT_RETRY_MAX_ATTEMPTS);
              assertThat(props.retry().baseBackoffMs())
                  .isEqualTo(AnalyticsProperties.DEFAULT_RETRY_BASE_BACKOFF_MS);
              assertThat(props.retry().maxBackoffMs())
                  .isEqualTo(AnalyticsProperties.DEFAULT_RETRY_MAX_BACKOFF_MS);
              assertThat(props.retry().jitter())
                  .isEqualTo(AnalyticsProperties.DEFAULT_RETRY_JITTER);
              assertThat(props.kafka().timeoutMs())
                  .isEqualTo(AnalyticsProperties.DEFAULT_KAFKA_TIMEOUT_MS);
              assertThat(props.retention().days())
                  .isEqualTo(AnalyticsProperties.DEFAULT_RETENTION_DAYS);
            });
  }

  /**
   * Given the salt env variable placeholder resolved from a property, then it wins over the
   * fallback.
   */
  @Test
  void saltPlaceholderReadsTheEnvironmentVariable() {
    runner
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withPropertyValues("SHORTENER_ANALYTICS_IP_SALT=from-environment-secret-value")
        .run(
            context ->
                assertThat(context.getBean(AnalyticsProperties.class).salt())
                    .isEqualTo("from-environment-secret-value"));
  }

  /** Given every knob set explicitly, when the context starts, then each override is bound. */
  @Test
  void propertiesOverrideDefaults() {
    runner
        .withPropertyValues(
            SALT,
            "analytics.outbox.poll-interval-ms=250",
            "analytics.outbox.batch-size=20",
            "analytics.retry.max-attempts=3",
            "analytics.retry.base-backoff-ms=50",
            "analytics.retry.max-backoff-ms=2000",
            "analytics.retry.jitter=equal",
            "analytics.kafka.timeout-ms=1500",
            "analytics.retention.days=30")
        .run(
            context -> {
              AnalyticsProperties props = context.getBean(AnalyticsProperties.class);
              assertThat(props.outbox().pollIntervalMs()).isEqualTo(250L);
              assertThat(props.outbox().batchSize()).isEqualTo(20);
              assertThat(props.retry().maxAttempts()).isEqualTo(3);
              assertThat(props.retry().baseBackoffMs()).isEqualTo(50L);
              assertThat(props.retry().maxBackoffMs()).isEqualTo(2000L);
              assertThat(props.retry().jitter()).isEqualTo(Jitter.EQUAL);
              assertThat(props.kafka().timeoutMs()).isEqualTo(1500L);
              assertThat(props.retention().days()).isEqualTo(30);
            });
  }

  /** Given no salt at all (no config files), then start-up fails: the secret is mandatory. */
  @Test
  void missingSaltFailsBinding() {
    runner.run(context -> assertThat(context).hasFailed());
  }

  /** Given a salt shorter than the minimum length, then start-up fails. */
  @Test
  void shortSaltFailsBinding() {
    runner
        .withPropertyValues("analytics.salt=too-short")
        .run(context -> assertThat(context).hasFailed());
  }

  /** Given a single out-of-bounds value, then start-up fails rather than the first relay pass. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "analytics.outbox.poll-interval-ms=10",
        "analytics.outbox.poll-interval-ms=60001",
        "analytics.outbox.batch-size=0",
        "analytics.outbox.batch-size=10001",
        "analytics.retry.max-attempts=0",
        "analytics.retry.max-attempts=101",
        "analytics.retry.base-backoff-ms=0",
        "analytics.retry.max-backoff-ms=0",
        "analytics.retry.jitter=random",
        "analytics.kafka.timeout-ms=99",
        "analytics.kafka.timeout-ms=60001",
        "analytics.retention.days=0",
        "analytics.retention.days=3651"
      })
  void outOfBoundsValuesFailBinding(String property) {
    runner.withPropertyValues(SALT, property).run(context -> assertThat(context).hasFailed());
  }

  /** Given a backoff cap below the base backoff, then the cross-field check rejects the context. */
  @Test
  void backoffCapBelowBaseFailsBinding() {
    runner
        .withPropertyValues(
            SALT, "analytics.retry.base-backoff-ms=5000", "analytics.retry.max-backoff-ms=1000")
        .run(context -> assertThat(context).hasFailed());
  }
}
