/*
 * KafkaUnavailableRedirectIT.java — redirects are unaffected by an unavailable broker (AC-3, AC-4)
 *
 * Layer: test (integration, task T8). Boots the application with spring.kafka.bootstrap-servers
 * pointing at a closed loopback port (no broker will ever answer) and proves that GET /{short_code}
 * keeps its 302 and Location header, answers promptly, and that the click stays PENDING in
 * click_outbox with no publish attempt made on the request path. Run only by failsafe under
 * ./mvnw -Pit verify; requires Docker for PostgreSQL and Redis.
 */
package com.example.shortener.analytics.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortener.analytics.domain.ClickOutboxEntry;
import com.example.shortener.analytics.domain.OutboxStatus;
import com.example.shortener.analytics.repository.ClickOutboxRepository;
import com.example.shortener.it.AbstractIntegrationTest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Broker outage from the redirect's point of view.
 *
 * <p>Fixture strategy: the broker address is a port nothing listens on, the producer's blocking
 * bound is short so any accidental send on the request thread would surface as a slow or failing
 * redirect, the listener is not started (there is nothing to consume) and the relay's tick is set
 * to its maximum so no pass runs during the test; the row can therefore only be in the state the
 * redirect left it in. The redirect must be indifferent to all of this: it records the click and
 * never touches Kafka (AC-3); the relay picks the row up later when the broker is back (AC-4).
 */
@TestPropertySource(
    properties = {
      "spring.kafka.bootstrap-servers=127.0.0.1:1",
      "spring.kafka.producer.properties.max.block.ms=3000",
      "analytics.consumer.auto-startup=false",
      "analytics.outbox.poll-interval-ms=60000"
    })
class KafkaUnavailableRedirectIT extends AbstractIntegrationTest {

  /** A redirect that waited for the (dead) broker would block for max.block.ms (3 s) first. */
  private static final Duration REQUEST_BUDGET = Duration.ofMillis(2000);

  /** Redirects issued before the timed one so JIT, pools and cache are warm. */
  private static final int WARMUP_REQUESTS = 5;

  @Autowired private ClickOutboxRepository outbox;

  @BeforeEach
  void emptyOutbox() {
    outbox.deleteAllInBatch();
  }

  /**
   * Given no reachable broker, when a short link is followed, then the redirect is a 302 with the
   * target in Location, the click is committed to the outbox as PENDING and no publish is attempted
   * on the request path.
   */
  @Test
  void redirectKeepsStatusAndLocationAndClickStaysPendingWhileBrokerIsDown() {
    String longUrl = uniqueLongUrl();
    String shortCode = createShortCode(longUrl);
    Instant before = Instant.now().minusSeconds(1);
    for (int i = 0; i < WARMUP_REQUESTS; i++) {
      assertThat(get("/" + shortCode).statusCode()).isEqualTo(302);
    }

    long started = System.nanoTime();
    HttpResponse<String> first = get("/" + shortCode);
    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
    HttpResponse<String> second = get("/" + shortCode);

    assertThat(first.statusCode()).isEqualTo(302);
    assertThat(header(first, "Location")).isEqualTo(longUrl);
    assertThat(second.statusCode()).isEqualTo(302);
    assertThat(header(second, "Location")).isEqualTo(longUrl);
    assertThat(elapsed)
        .as("a redirect must not wait for the broker (max.block.ms is 3 s)")
        .isLessThan(REQUEST_BUDGET);

    List<ClickOutboxEntry> rows = outbox.findAll();
    assertThat(rows).hasSize(WARMUP_REQUESTS + 2);
    assertThat(rows)
        .allSatisfy(
            row -> {
              assertThat(row.getShortCode()).isEqualTo(shortCode);
              assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
              assertThat(row.getAttempts()).isZero();
              assertThat(row.getPublishedAt()).isNull();
              assertThat(row.getOccurredAt()).isAfterOrEqualTo(before);
              assertThat(row.getNextAttemptAt()).isNotNull();
            });
    assertThat(outbox.countByStatus(OutboxStatus.PENDING)).isEqualTo(WARMUP_REQUESTS + 2);
    assertThat(outbox.countByStatus(OutboxStatus.PUBLISHED)).isZero();
    assertThat(outbox.countByStatus(OutboxStatus.FAILED)).isZero();
  }

  /** Given no reachable broker, when an unknown code is requested, then 404 and no outbox row. */
  @Test
  void unknownCodeIsStillA404WithoutAnOutboxRow() {
    assertProblem(get("/doesNotExistKafkaDown"), 404);

    assertThat(outbox.count()).isZero();
  }
}
