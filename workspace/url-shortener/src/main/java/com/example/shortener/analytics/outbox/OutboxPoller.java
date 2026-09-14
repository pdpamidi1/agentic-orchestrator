/*
 * OutboxPoller.java — @Scheduled trigger of the outbox relay
 *
 * Layer: analytics.outbox. Fires every analytics.outbox.poll-interval-ms on the dedicated
 * analyticsTaskScheduler (OutboxSchedulingConfig) and asks OutboxPublishService for one pass.
 * Never runs on a request thread, so a slow or unavailable broker cannot delay a redirect (AC-3).
 * Any exception of a pass is logged and swallowed so the schedule keeps running.
 */
package com.example.shortener.analytics.outbox;

import com.example.shortener.analytics.outbox.OutboxPublishService.PublishSummary;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-process poller of the click outbox.
 *
 * <p><b>Responsibility.</b> Call {@link OutboxPublishService#publishDueBatch()} once per tick and
 * contain its failures. Ticks use a fixed delay measured from the end of the previous pass, so two
 * passes of one instance never overlap; across instances the {@code SKIP LOCKED} claim guarantees
 * disjoint batches.
 *
 * <p><b>Thread-safety and lifecycle.</b> Immutable singleton bean; {@link #poll()} is invoked by
 * the single scheduler thread only.
 */
@Component
public class OutboxPoller {

  private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

  /** Property that sets the delay between two passes. */
  public static final String POLL_INTERVAL_PROPERTY = "analytics.outbox.poll-interval-ms";

  private final OutboxPublishService publishService;

  /**
   * Creates the poller.
   *
   * @param publishService the relay unit of work
   * @throws NullPointerException when {@code publishService} is {@code null}
   */
  public OutboxPoller(OutboxPublishService publishService) {
    this.publishService = Objects.requireNonNull(publishService, "publishService");
  }

  /**
   * One relay tick: publishes a single batch of due rows. Runs on {@link
   * OutboxSchedulingConfig#SCHEDULER_BEAN}, never on a request thread.
   *
   * @return what the pass did, or {@link PublishSummary#EMPTY} when the pass failed
   */
  @Scheduled(
      fixedDelayString = "${" + POLL_INTERVAL_PROPERTY + ":500}",
      initialDelayString = "${" + POLL_INTERVAL_PROPERTY + ":500}",
      scheduler = OutboxSchedulingConfig.SCHEDULER_BEAN)
  public PublishSummary poll() {
    try {
      return publishService.publishDueBatch();
    } catch (RuntimeException e) {
      log.error("Outbox pass failed; rows stay PENDING for the next tick: {}", e.toString());
      return PublishSummary.EMPTY;
    }
  }
}
