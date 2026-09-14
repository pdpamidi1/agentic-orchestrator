/*
 * RedirectLatencyBudgetIT.java — p95 latency gate of GET /{short_code} (AC-13)
 *
 * Layer: test (integration, task T8). Measures the p95 latency of the redirect with click recording
 * enabled against the baseline recorded in src/test/resources/analytics/redirect-latency-baseline
 * .properties and fails when the increase exceeds the tolerance (5 %). Shares the base fixture's
 * application context (PostgreSQL + Redis, no broker: the relay runs on its own scheduler thread
 * and must not influence the request path, which is exactly what this gate checks). Run only by
 * failsafe under ./mvnw -Pit verify; requires Docker.
 */
package com.example.shortener.analytics.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortener.it.AbstractIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Latency budget of the redirect path.
 *
 * <p>Protocol: one short link is created; {@code warmup-requests} redirects warm the JIT, the
 * connection pool and the URL cache; {@code sample-requests} redirects are then timed end to end
 * with the JDK client (nearest-rank p95 over wall-clock nanoseconds). Every request goes through
 * the full production path including the {@code click_outbox} insert, so the number is the cost the
 * analytics feature adds on top of the baseline.
 *
 * <p>Baseline: {@value #BASELINE_RESOURCE} ({@code p95-ms}, {@code tolerance-percent}). It can be
 * overridden with {@code -Dredirect.latency.baseline-p95-ms}, and {@code
 * -Dredirect.latency.record=true} switches the gate into record mode: the measured p95 is written
 * to {@code target/redirect-latency-baseline.properties} and the test passes, so a new baseline can
 * be reviewed and committed.
 */
class RedirectLatencyBudgetIT extends AbstractIntegrationTest {

  /** Classpath location of the recorded baseline. */
  static final String BASELINE_RESOURCE = "analytics/redirect-latency-baseline.properties";

  /** System property overriding the recorded p95 (milliseconds). */
  static final String BASELINE_OVERRIDE_PROPERTY = "redirect.latency.baseline-p95-ms";

  /** System property switching the gate into record mode. */
  static final String RECORD_PROPERTY = "redirect.latency.record";

  /** Where record mode writes the new baseline. */
  static final Path RECORDED_BASELINE = Path.of("target", "redirect-latency-baseline.properties");

  /**
   * Given the recorded baseline, when the redirect is sampled with click recording enabled, then
   * its p95 does not exceed the baseline by more than the tolerance.
   */
  @Test
  void redirectP95StaysWithinBudget() {
    Baseline baseline = Baseline.load();
    String shortCode = createShortCode(uniqueLongUrl());
    String path = "/" + shortCode;

    for (int i = 0; i < baseline.warmupRequests(); i++) {
      assertRedirect(get(path));
    }
    long[] samples = new long[baseline.sampleRequests()];
    for (int i = 0; i < samples.length; i++) {
      long started = System.nanoTime();
      HttpResponse<String> response = get(path);
      samples[i] = System.nanoTime() - started;
      assertRedirect(response);
    }

    double p95Ms = percentile(samples, 95) / 1_000_000.0;
    double medianMs = percentile(samples, 50) / 1_000_000.0;
    double budgetMs = baseline.p95Ms() * (1.0 + baseline.tolerancePercent() / 100.0);
    String report =
        String.format(
            Locale.ROOT,
            "redirect latency: p50=%.2f ms, p95=%.2f ms over %d samples; baseline p95=%.2f ms,"
                + " budget=%.2f ms (+%.1f %%)",
            medianMs,
            p95Ms,
            samples.length,
            baseline.p95Ms(),
            budgetMs,
            baseline.tolerancePercent());
    System.out.println(report);

    if (Boolean.getBoolean(RECORD_PROPERTY)) {
      record(p95Ms, baseline);
      return;
    }
    assertThat(p95Ms)
        .as("%s; re-record with -D%s=true if the baseline is stale", report, RECORD_PROPERTY)
        .isLessThanOrEqualTo(budgetMs);
  }

  private static void assertRedirect(HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(header(response, "Location")).isNotNull();
  }

  /** Nearest-rank percentile of the samples (nanoseconds). */
  static long percentile(long[] samples, int percentile) {
    long[] sorted = samples.clone();
    Arrays.sort(sorted);
    int rank = (int) Math.ceil(percentile / 100.0 * sorted.length);
    return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
  }

  private static void record(double p95Ms, Baseline baseline) {
    String content =
        String.format(
            Locale.ROOT,
            "# Recorded by RedirectLatencyBudgetIT (-D%s=true); review and copy to"
                + " src/test/resources/%s%n"
                + "p95-ms=%d%n"
                + "tolerance-percent=%.0f%n"
                + "warmup-requests=%d%n"
                + "sample-requests=%d%n",
            RECORD_PROPERTY,
            BASELINE_RESOURCE,
            (long) Math.ceil(p95Ms),
            baseline.tolerancePercent(),
            baseline.warmupRequests(),
            baseline.sampleRequests());
    try {
      Files.createDirectories(RECORDED_BASELINE.getParent());
      Files.writeString(RECORDED_BASELINE, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The recorded baseline and the sampling parameters. */
  record Baseline(double p95Ms, double tolerancePercent, int warmupRequests, int sampleRequests) {

    static Baseline load() {
      Properties properties = new Properties();
      try (InputStream in =
          RedirectLatencyBudgetIT.class.getClassLoader().getResourceAsStream(BASELINE_RESOURCE)) {
        assertThat(in).as("baseline resource %s", BASELINE_RESOURCE).isNotNull();
        properties.load(in);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      double p95 =
          Double.parseDouble(
              System.getProperty(
                  BASELINE_OVERRIDE_PROPERTY, properties.getProperty("p95-ms", "50")));
      Baseline baseline =
          new Baseline(
              p95,
              Double.parseDouble(properties.getProperty("tolerance-percent", "5")),
              Integer.parseInt(properties.getProperty("warmup-requests", "200")),
              Integer.parseInt(properties.getProperty("sample-requests", "400")));
      assertThat(baseline.p95Ms()).isPositive();
      assertThat(baseline.tolerancePercent()).isBetween(0.0, 100.0);
      assertThat(baseline.sampleRequests()).isGreaterThanOrEqualTo(20);
      return baseline;
    }
  }
}
