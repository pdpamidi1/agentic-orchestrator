/*
 * ConsumerMetrics.java — counters of the url.clicked consumer
 *
 * Layer: analytics.consumer. Micrometer core is not on the classpath (only micrometer-observation
 * comes with Spring Kafka), so the consumer counts through this small bean, mirroring
 * analytics.outbox.OutboxMetrics; a MeterRegistry can be bound to its getters when the actuator is
 * added. Incremented by ClickEventConsumer for every terminal outcome of a delivery (AC-5, AC-6).
 */
package com.example.shortener.analytics.consumer;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Process-wide counters of the click-event consumer.
 *
 * <p><b>Thread-safety.</b> Singleton bean backed by atomics; safe for concurrent use.
 */
@Component
public class ConsumerMetrics {

  /** Events applied to the aggregates for the first time. */
  private final AtomicLong applied = new AtomicLong();

  /** Redeliveries skipped because the idempotency key was already processed. */
  private final AtomicLong duplicate = new AtomicLong();

  /** Records skipped because they could not be deserialised into a valid event. */
  private final AtomicLong rejected = new AtomicLong();

  /** Events dropped after the retry budget was exhausted. */
  private final AtomicLong exhausted = new AtomicLong();

  /** Counts one applied event. */
  public void incrementApplied() {
    applied.incrementAndGet();
  }

  /** Counts one skipped duplicate. */
  public void incrementDuplicate() {
    duplicate.incrementAndGet();
  }

  /** Counts one rejected (malformed) record. */
  public void incrementRejected() {
    rejected.incrementAndGet();
  }

  /** Counts one event dropped after exhausting the retry budget. */
  public void incrementExhausted() {
    exhausted.incrementAndGet();
  }

  /** Events applied since start-up. */
  public long appliedCount() {
    return applied.get();
  }

  /** Duplicates skipped since start-up. */
  public long duplicateCount() {
    return duplicate.get();
  }

  /** Malformed records rejected since start-up. */
  public long rejectedCount() {
    return rejected.get();
  }

  /** Events dropped after retry exhaustion since start-up. */
  public long exhaustedCount() {
    return exhausted.get();
  }
}
