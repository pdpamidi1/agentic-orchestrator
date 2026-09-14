/*
 * RedirectClickRecordingIT.java — click_outbox rows committed by real redirects, end to end
 *
 * Layer: test (integration). Boots the whole application on Testcontainers Postgres/Redis via
 * AbstractIntegrationTest and proves over real HTTP: one 302 commits exactly one PENDING
 * click_outbox row with a UUID idempotency key, the short code, a UTC occurred_at, a salted hash of
 * the peer address (never the address) and the Referer host (AC-1, AC-10, AC-11, AC-14); two
 * redirects give two rows with distinct keys; a cache hit records too; 404 and 410 commit nothing
 * (AC-2); the response is written only after the row is visible (AC-3). Requires Docker; run by
 * failsafe under ./mvnw -Pit verify.
 */
package com.example.shortener.redirect;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortener.analytics.config.AnalyticsProperties;
import com.example.shortener.analytics.domain.ClickOutboxEntry;
import com.example.shortener.analytics.domain.OutboxStatus;
import com.example.shortener.analytics.recording.IpHasher;
import com.example.shortener.analytics.repository.ClickOutboxRepository;
import com.example.shortener.domain.CodeSource;
import com.example.shortener.domain.UrlMapping;
import com.example.shortener.it.AbstractIntegrationTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Transactional click recording on {@code GET /{short_code}} against the real stack. */
class RedirectClickRecordingIT extends AbstractIntegrationTest {

  /** Loopback addresses the JDK client may connect from; the peer address is one of them. */
  private static final List<String> LOOPBACK = List.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");

  private static final HttpClient CLIENT =
      HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NEVER)
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  @Autowired private ClickOutboxRepository outbox;

  @Autowired private AnalyticsProperties analyticsProperties;

  @BeforeEach
  void emptyOutbox() {
    outbox.deleteAllInBatch();
  }

  /** One redirect with a Referer commits exactly one fully populated PENDING row. */
  @Test
  void successfulRedirectCommitsExactlyOneOutboxRow() {
    String shortCode = createShortCode(uniqueLongUrl());
    Instant before = Instant.now().minusSeconds(1);

    HttpResponse<String> response =
        getWithReferer("/" + shortCode, "https://News.Example.org/x?y=1");

    assertThat(response.statusCode()).isEqualTo(302);
    List<ClickOutboxEntry> rows = outbox.findAll();
    assertThat(rows).hasSize(1);
    ClickOutboxEntry row = rows.get(0);
    assertThat(row.getShortCode()).isEqualTo(shortCode);
    assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
    assertThat(row.getAttempts()).isZero();
    assertThat(row.getPublishedAt()).isNull();
    assertThat(UUID.fromString(row.getIdempotencyKey()).toString())
        .isEqualTo(row.getIdempotencyKey());
    assertThat(row.getOccurredAt()).isBetween(before, Instant.now().plusSeconds(1));
    assertThat(row.getCreatedAt()).isEqualTo(row.getOccurredAt());
    assertThat(row.getReferrerHost()).isEqualTo("news.example.org");
    IpHasher hasher = new IpHasher(analyticsProperties);
    assertThat(row.getHashedIp())
        .matches("^[0-9a-f]{64}$")
        .isIn(LOOPBACK.stream().map(hasher::hash).toList());
    for (String address : LOOPBACK) {
      assertThat(row.getHashedIp()).doesNotContain(address);
    }
  }

  /** Two redirects (second one a cache hit) commit two rows with distinct idempotency keys. */
  @Test
  void everyRedirectIncludingCacheHitsRecordsItsOwnRow() {
    String shortCode = createShortCode(uniqueLongUrl());

    assertThat(get("/" + shortCode).statusCode()).isEqualTo(302);
    assertThat(get("/" + shortCode).statusCode()).isEqualTo(302);

    List<ClickOutboxEntry> rows = outbox.findAll();
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).getIdempotencyKey()).isNotEqualTo(rows.get(1).getIdempotencyKey());
    assertThat(rows).allSatisfy(row -> assertThat(row.getReferrerHost()).isNull());
  }

  /** Unknown and expired codes leave the outbox empty. */
  @Test
  void unknownAndExpiredCodesRecordNothing() {
    Instant now = Instant.now();
    repository.saveAndFlush(
        new UrlMapping(
            "expiredIT2",
            "https://example.com/expired",
            now.minus(2, ChronoUnit.DAYS),
            now.minus(1, ChronoUnit.DAYS),
            CodeSource.REDIS));

    assertThat(getWithReferer("/doesNotExist", "https://example.com/").statusCode()).isEqualTo(404);
    assertThat(getWithReferer("/expiredIT2", "https://example.com/").statusCode()).isEqualTo(410);

    assertThat(outbox.count()).isZero();
  }

  private HttpResponse<String> getWithReferer(String path, String referer) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(origin() + path))
            .header("Referer", referer)
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build();
    try {
      return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for the response", e);
    }
  }
}
