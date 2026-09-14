/*
 * ClickAnalyticsEndToEndIT.java — whole click-analytics pipeline against real infrastructure
 *
 * Layer: test (integration, task T8). Boots the application against Testcontainers PostgreSQL,
 * Redis and Kafka (apache/kafka) and drives one click through every stage: redirect (AC-1) ->
 * click_outbox row (AC-3) -> outbox relay publish on url.clicked (AC-4) -> consumer aggregation
 * (AC-6) -> GET /api/v1/urls/{short_code}/stats (AC-8). A second delivery of the very same record
 * proves exactly-once aggregation, and unknown short codes produce neither a row nor an event.
 * Run only by failsafe under ./mvnw -Pit verify (AC-14, AC-15); requires Docker.
 */
package com.example.shortener.analytics.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.shortener.analytics.consumer.ConsumerMetrics;
import com.example.shortener.analytics.domain.ClickOutboxEntry;
import com.example.shortener.analytics.domain.OutboxStatus;
import com.example.shortener.analytics.kafka.ClickEventMessage;
import com.example.shortener.analytics.kafka.ClickEventProducer;
import com.example.shortener.analytics.repository.ClickOutboxRepository;
import com.example.shortener.analytics.repository.ClickStatsRepository;
import com.example.shortener.analytics.repository.ProcessedClickEventRepository;
import com.example.shortener.it.AbstractIntegrationTest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end proof of the analytics pipeline with a real broker.
 *
 * <p>The Kafka container is started once per JVM in a static initialiser (same reasoning as the
 * PostgreSQL and Redis containers of {@link AbstractIntegrationTest}); the {@code url.clicked}
 * topic is created up front so the listener is subscribed before the first event is relayed. The
 * relay is sped up to one pass every 100 ms and the consumer reads from the earliest offset so
 * nothing produced before the partition assignment is missed.
 *
 * <p>Fixture strategy: this class adds properties to the shared {@code @SpringBootTest}, so it
 * boots its own cached application context wired to all three containers; the analytics tables are
 * emptied before every test, the {@code urls} table and the URL cache by the base class.
 */
@TestPropertySource(
    properties = {
      "analytics.outbox.poll-interval-ms=100",
      "analytics.retry.max-attempts=3",
      "spring.kafka.consumer.auto-offset-reset=earliest",
      "spring.kafka.producer.properties.max.block.ms=10000"
    })
class ClickAnalyticsEndToEndIT extends AbstractIntegrationTest {

  /** Apache Kafka image (KRaft, no ZooKeeper) supported by testcontainers-kafka 2.x. */
  static final String KAFKA_IMAGE = "apache/kafka:3.8.0";

  /** Route of the stats endpoint (operationId {@code getUrlClickStats}). */
  static final String STATS_PATH = "/api/v1/urls/%s/stats";

  private static final Duration PIPELINE_TIMEOUT = Duration.ofSeconds(60);

  @SuppressWarnings("resource")
  static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE));

  static {
    KAFKA.start();
    createTopic();
  }

  @Autowired private ClickOutboxRepository outbox;
  @Autowired private ClickStatsRepository stats;
  @Autowired private ProcessedClickEventRepository processed;
  @Autowired private ClickEventProducer producer;
  @Autowired private ConsumerMetrics metrics;
  @Autowired private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }

  private static void createTopic() {
    Map<String, Object> config =
        Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    try (AdminClient admin = AdminClient.create(config)) {
      admin
          .createTopics(List.of(new NewTopic(ClickEventProducer.TOPIC, 1, (short) 1)))
          .all()
          .get(30, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      if (!(e.getCause() instanceof TopicExistsException)) {
        throw new IllegalStateException("could not create topic " + ClickEventProducer.TOPIC, e);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while creating the topic", e);
    } catch (TimeoutException e) {
      throw new IllegalStateException("timed out creating topic " + ClickEventProducer.TOPIC, e);
    }
  }

  @BeforeEach
  void resetAnalyticsTables() {
    jdbc.update("DELETE FROM click_stats_daily");
    jdbc.update("DELETE FROM click_stats");
    jdbc.update("DELETE FROM processed_click_event");
    jdbc.update("DELETE FROM click_outbox");
  }

  /**
   * Given a short link, when it is followed once, then the redirect answers 302 with the target in
   * Location, exactly one outbox row is committed, the relay publishes it, the consumer aggregates
   * it and the stats endpoint reports the click; a redelivery of the same record changes nothing.
   */
  @Test
  void clickFlowsFromRedirectThroughOutboxAndKafkaToStatsExactlyOnce() {
    String longUrl = uniqueLongUrl();
    String shortCode = createShortCode(longUrl);
    long appliedBefore = metrics.appliedCount();
    long duplicatesBefore = metrics.duplicateCount();

    // AC-1: redirect with the target in Location.
    HttpResponse<String> redirect = get("/" + shortCode);
    assertThat(redirect.statusCode()).isEqualTo(302);
    assertThat(header(redirect, "Location")).isEqualTo(longUrl);

    // AC-3: exactly one outbox row for this click, committed with the redirect.
    List<ClickOutboxEntry> rows = outbox.findAll();
    assertThat(rows).hasSize(1);
    ClickOutboxEntry row = rows.getFirst();
    assertThat(row.getShortCode()).isEqualTo(shortCode);
    assertThat(row.getStatus()).isIn(OutboxStatus.PENDING, OutboxStatus.PUBLISHED);

    // AC-4: the relay (not the request thread) publishes it.
    await()
        .atMost(PIPELINE_TIMEOUT)
        .untilAsserted(
            () -> {
              ClickOutboxEntry relayed = outbox.findById(row.getId()).orElseThrow();
              assertThat(relayed.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
              assertThat(relayed.getPublishedAt()).isNotNull();
            });

    // AC-6: the consumer aggregates it once.
    await()
        .atMost(PIPELINE_TIMEOUT)
        .untilAsserted(
            () -> {
              assertThat(processed.existsById(row.getIdempotencyKey())).isTrue();
              assertThat(stats.findById(shortCode).orElseThrow().getTotalClicks()).isEqualTo(1);
              assertThat(metrics.appliedCount()).isEqualTo(appliedBefore + 1);
            });

    // AC-8: the stats endpoint reports the click.
    Map<String, Object> body = assertStats(shortCode);
    assertThat(body.get("total_clicks")).isEqualTo(1);
    assertThat(body.get("last_clicked_at")).isNotNull();
    assertThat(body.get("as_of")).isNotNull();
    assertThat(clicksByDay(body))
        .singleElement()
        .satisfies(
            day -> {
              assertThat(day.get("date")).isEqualTo(LocalDate.now(ZoneOffset.UTC).toString());
              assertThat(day.get("count")).isEqualTo(1);
            });

    // Exactly-once: redeliver the identical record (same idempotency-key header and payload).
    ClickEventMessage duplicate =
        new ClickEventMessage(
            row.getIdempotencyKey(),
            row.getShortCode(),
            row.getOccurredAt(),
            row.getHashedIp(),
            row.getReferrerHost());
    producer.send(duplicate).orTimeout(30, TimeUnit.SECONDS).join();
    await()
        .atMost(PIPELINE_TIMEOUT)
        .untilAsserted(() -> assertThat(metrics.duplicateCount()).isEqualTo(duplicatesBefore + 1));

    assertThat(metrics.appliedCount()).isEqualTo(appliedBefore + 1);
    assertThat(processed.count()).isEqualTo(1);
    assertThat(stats.findById(shortCode).orElseThrow().getTotalClicks()).isEqualTo(1);
    Map<String, Object> after = assertStats(shortCode);
    assertThat(after.get("total_clicks")).isEqualTo(1);
    assertThat(after.get("last_clicked_at")).isEqualTo(body.get("last_clicked_at"));
    assertThat(clicksByDay(after)).hasSize(1);
    assertThat(clicksByDay(after).getFirst().get("count")).isEqualTo(1);
  }

  /**
   * Given an unknown short code, when it is requested, then the redirect answers 404 and neither an
   * outbox row nor a url.clicked event exists; its stats are 404 as well.
   */
  @Test
  void unknownShortCodeRecordsNothing() {
    long appliedBefore = metrics.appliedCount();
    long rejectedBefore = metrics.rejectedCount();

    assertProblem(get("/doesNotExistE2E"), 404);

    assertThat(outbox.count()).isZero();
    assertProblem(get(STATS_PATH.formatted("doesNotExistE2E")), 404);

    // Give the relay a few passes: nothing must reach the consumer.
    await()
        .during(Duration.ofSeconds(1))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(outbox.count()).isZero();
              assertThat(metrics.appliedCount()).isEqualTo(appliedBefore);
              assertThat(metrics.rejectedCount()).isEqualTo(rejectedBefore);
            });
  }

  /** Given a link that was never followed, when its stats are read, then 0 / null / []. */
  @Test
  void neverClickedLinkReportsZeroStats() {
    String shortCode = createShortCode(uniqueLongUrl());

    Map<String, Object> body = assertStats(shortCode);

    assertThat(body.get("total_clicks")).isEqualTo(0);
    assertThat(body.get("last_clicked_at")).isNull();
    assertThat(body.get("as_of")).isNotNull();
    assertThat(clicksByDay(body)).isEmpty();
  }

  private Map<String, Object> assertStats(String shortCode) {
    HttpResponse<String> response = get(STATS_PATH.formatted(shortCode));
    assertThat(response.statusCode())
        .as("stats of %s: %s", shortCode, response.body())
        .isEqualTo(200);
    assertThat(contentType(response)).startsWith("application/json");
    Map<String, Object> body = json(response.body());
    assertThat(body).containsKeys("total_clicks", "last_clicked_at", "as_of", "clicks_by_day");
    return body;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> clicksByDay(Map<String, Object> body) {
    Object days = body.get("clicks_by_day");
    assertThat(days).isInstanceOf(List.class);
    return (List<Map<String, Object>>) days;
  }
}
