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
 */
class ShortenerPropertiesTest {

  @EnableConfigurationProperties(ShortenerProperties.class)
  @Configuration(proxyBeanMethods = false)
  static class PropertiesConfig {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class);

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

  @Test
  void invalidValuesFailBinding() {
    runner
        .withPropertyValues("shortener.counter-batch-size=0")
        .run(context -> assertThat(context).hasFailed());
  }
}
