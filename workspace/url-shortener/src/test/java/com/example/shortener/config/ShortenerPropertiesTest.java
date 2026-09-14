/*
 * ShortenerPropertiesTest.java — binding, defaults and validation of the shortener.* properties.
 *
 * Layer: test (unit). Pins the documented defaults of ShortenerProperties (base-url
 * http://localhost:8080, counter-batch-size 1000, cache-ttl 24h, counter-seed-offset 62^5), that
 * application.yml repeats the same values, that explicit properties override them (with the
 * trailing slash of base-url stripped) and that an invalid value fails context start-up.
 * Technique: Spring Boot's ApplicationContextRunner with a one-line @Configuration; no web server,
 * no database, no Redis, no containers. Run with ./mvnw test.
 */
package com.example.shortener.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Verifies the documented defaults of {@link ShortenerProperties} using a plain application context
 * (no web server, no database, no Redis).
 *
 * <p>Fixture strategy: an {@link ApplicationContextRunner} per test instance registers only {@link
 * PropertiesConfig}, which enables binding of {@code shortener.*} into the record. Each test
 * refines the runner (property values, or the config-data initializer that loads {@code
 * application.yml} from the classpath) and runs a fresh, throw-away context. No profile is active,
 * so {@code application-read.yml} / {@code application-write.yml} are not involved.
 *
 * <p>Removing this class would let the code defaults ({@code @DefaultValue}) and the values in
 * {@code application.yml} drift apart unnoticed, and would leave the {@code @Validated} guard on
 * {@code counter-batch-size} without a test.
 */
class ShortenerPropertiesTest {

  /** Smallest possible user configuration: just enables binding of the properties record. */
  @EnableConfigurationProperties(ShortenerProperties.class)
  @Configuration(proxyBeanMethods = false)
  static class PropertiesConfig {}

  /** Base runner; tests derive from it so each context is independent. */
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class);

  /**
   * Given no {@code shortener.*} property at all (and no config files), when the context starts,
   * then the record carries the {@code @DefaultValue}s: localhost:8080, 1000, 24h and 916,132,832.
   */
  @Test
  void codeDefaultsApplyWhenNothingIsConfigured() {
    runner.run(
        context -> {
          ShortenerProperties props = context.getBean(ShortenerProperties.class);
          assertThat(props.baseUrl()).isEqualTo("http://localhost:8080");
          assertThat(props.counterBatchSize()).isEqualTo(1000);
          assertThat(props.cacheTtl()).isEqualTo(Duration.ofHours(24));
          assertThat(props.counterSeedOffset()).isEqualTo(916_132_832L);
        });
  }

  /**
   * Given the real {@code application.yml} loaded through {@link
   * ConfigDataApplicationContextInitializer}, when the context starts, then the bound values equal
   * the {@code DEFAULT_*} constants, i.e. the YAML documents exactly the code defaults.
   */
  @Test
  void applicationYamlDocumentsTheSameDefaults() {
    runner
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .run(
            context -> {
              ShortenerProperties props = context.getBean(ShortenerProperties.class);
              assertThat(props.baseUrl()).isEqualTo(ShortenerProperties.DEFAULT_BASE_URL);
              assertThat(props.counterBatchSize())
                  .isEqualTo(ShortenerProperties.DEFAULT_COUNTER_BATCH_SIZE);
              assertThat(props.cacheTtl()).isEqualTo(ShortenerProperties.DEFAULT_CACHE_TTL);
              assertThat(props.counterSeedOffset())
                  .isEqualTo(ShortenerProperties.DEFAULT_COUNTER_SEED_OFFSET);
            });
  }

  /**
   * Given all four properties set explicitly (base URL with a trailing slash, 250, 15m, 42), when
   * the context starts, then each override is bound and the trailing slash has been removed by the
   * record's canonical constructor.
   */
  @Test
  void propertiesOverrideDefaultsAndBaseUrlLosesTrailingSlash() {
    runner
        .withPropertyValues(
            "shortener.base-url=https://sho.rt/",
            "shortener.counter-batch-size=250",
            "shortener.cache-ttl=15m",
            "shortener.counter-seed-offset=42")
        .run(
            context -> {
              ShortenerProperties props = context.getBean(ShortenerProperties.class);
              assertThat(props.baseUrl()).isEqualTo("https://sho.rt");
              assertThat(props.counterBatchSize()).isEqualTo(250);
              assertThat(props.cacheTtl()).isEqualTo(Duration.ofMinutes(15));
              assertThat(props.counterSeedOffset()).isEqualTo(42L);
            });
  }

  /**
   * Given {@code counter-batch-size = 0} (violates {@code @Min(1)}), when the context starts, then
   * start-up fails: misconfiguration is rejected at boot rather than at the first allocation.
   */
  @Test
  void invalidValuesFailBinding() {
    runner
        .withPropertyValues("shortener.counter-batch-size=0")
        .run(context -> assertThat(context).hasFailed());
  }
}
