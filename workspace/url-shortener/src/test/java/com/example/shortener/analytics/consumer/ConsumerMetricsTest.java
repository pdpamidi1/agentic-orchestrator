/*
 * ConsumerMetricsTest.java — the four consumer counters start at zero and count independently
 *
 * Layer: test (unit). No Spring context.
 */
package com.example.shortener.analytics.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests of {@link ConsumerMetrics}. */
class ConsumerMetricsTest {

  @Test
  void countersStartAtZeroAndCountIndependently() {
    ConsumerMetrics metrics = new ConsumerMetrics();
    assertThat(metrics.appliedCount()).isZero();
    assertThat(metrics.duplicateCount()).isZero();
    assertThat(metrics.rejectedCount()).isZero();
    assertThat(metrics.exhaustedCount()).isZero();

    metrics.incrementApplied();
    metrics.incrementApplied();
    metrics.incrementDuplicate();
    metrics.incrementRejected();
    metrics.incrementExhausted();
    metrics.incrementExhausted();
    metrics.incrementExhausted();

    assertThat(metrics.appliedCount()).isEqualTo(2);
    assertThat(metrics.duplicateCount()).isEqualTo(1);
    assertThat(metrics.rejectedCount()).isEqualTo(1);
    assertThat(metrics.exhaustedCount()).isEqualTo(3);
  }
}
