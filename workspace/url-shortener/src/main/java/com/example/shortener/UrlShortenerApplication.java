/*
 * UrlShortenerApplication.java — Spring Boot entry point of the url-shortener service
 *
 * Layer: application. Bootstraps the one Spring context that can host both HTTP surfaces: the
 * read surface (GET /{short_code}, package read) and the write surface (POST /api/v1/urls,
 * package write). Which surface an instance actually serves is decided by SPRING_PROFILES_ACTIVE
 * through the @Profile gates on the two controllers, never in this class (AC-14). It also switches
 * on the binding of the shortener.* namespace (ShortenerProperties: base URL, counter batch size,
 * cache TTL, counter seed offset) that the read and write services consume.
 */
package com.example.shortener;

import com.example.shortener.config.ShortenerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point of the URL shortener service.
 *
 * <p>Responsibility: start the Spring Boot application context and nothing else. Component scanning
 * covers the whole {@code com.example.shortener} tree, so the read side ({@code
 * RedirectController}, {@code UrlReadService}, {@code RedisUrlCache}), the write side ({@code
 * UrlWriteService}, {@code UrlWriteController}), the code generators and the global exception
 * handler are all discovered; the two profile-gated controllers then drop out of the context on
 * instances started with {@code SPRING_PROFILES_ACTIVE=read} or {@code write} (AC-14). Flyway runs
 * the {@code urls} migrations on start-up and Hibernate only {@code validate}s the schema.
 *
 * <p>{@link EnableConfigurationProperties} registers the immutable {@link ShortenerProperties}
 * record so the {@code shortener.*} settings are bound and validated once at boot and injected as
 * an ordinary bean wherever the cache TTL, base URL or counter settings are needed.
 *
 * <p>Invariants and thread-safety: the class holds no state and is never instantiated by
 * application code; Spring creates the single configuration bean, so there is nothing to
 * synchronise.
 */
@SpringBootApplication
@EnableConfigurationProperties(ShortenerProperties.class)
public class UrlShortenerApplication {

  /**
   * Boots the service.
   *
   * <p>Delegates to {@link SpringApplication#run}, which reads {@code application.yml}, the
   * profile-specific {@code application-read.yml} / {@code application-write.yml} and the
   * environment, builds the context and starts the embedded Tomcat on {@code server.port}.
   *
   * @param args command-line arguments forwarded to Spring Boot (for example {@code
   *     --spring.profiles.active=read}); may be empty, never {@code null} when launched by the JVM
   */
  public static void main(String[] args) {
    SpringApplication.run(UrlShortenerApplication.class, args);
  }
}
