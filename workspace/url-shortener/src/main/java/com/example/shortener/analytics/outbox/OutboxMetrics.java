/*
 * OutboxMetrics.java — counters of the outbox relay
 *
 * Layer: analytics.outbox. Micrometer core is not on the classpath (only micrometer-observation
 * comes with Spring Kafka), so the relay counts through this small bean; a MeterRegistry can be
 * bound to its getters when the actuator is added. Incremented by OutboxPublishService when a row
 * is parked as FAILED after the retry budget is exhausted (AC-5).
 */
package com.example.shortener.analytics.outbox;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Process-wide counters of the outbox relay.
 *
 * <p><b>Thread-safety.</b> Singleton bean backed by atomics; safe for concurrent use.
 */
@Component
public class OutboxMetrics {

  /** Rows parked as {@code FAILED} after exhausting the retry budget. */
  private final AtomicLong publishFailed = new AtomicLong();

  /** Rows acknowledged by Kafka and marked {@code PUBLISHED}. */
  private final AtomicLong published = new AtomicLong();

  /** Counts one row parked as {@code FAILED}. */
  public void incrementPublishFailed() {
    publishFailed.incrementAndGet();
  }

  /** Counts one row marked {@code PUBLISHED}. */
  public void incrementPublished() {
    published.incrementAndGet();
  }

  /** Rows parked as {@code FAILED} since start-up. */
  public long publishFailedCount() {
    return publishFailed.get();
  }

  /** Rows marked {@code PUBLISHED} since start-up. */
  public long publishedCount() {
    return published.get();
  }
}
