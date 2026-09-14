/*
 * OutboxPollerTest.java — the poller delegates, contains failures and runs off request threads
 *
 * Layer: test (unit). Pins that poll() calls OutboxPublishService once and returns its summary,
 * swallows a failing pass, is @Scheduled with a fixed delay bound to
 * analytics.outbox.poll-interval-ms on the dedicated analyticsTaskScheduler, and that
 * OutboxSchedulingConfig enables scheduling and builds a single-threaded scheduler whose threads
 * carry the analytics prefix (AC-3). No Spring context.
 */
package com.example.shortener.analytics.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.shortener.analytics.outbox.OutboxPublishService.PublishSummary;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Unit tests of {@link OutboxPoller} and {@link OutboxSchedulingConfig}. */
class OutboxPollerTest {

  private final OutboxPublishService service = mock(OutboxPublishService.class);
  private final OutboxPoller poller = new OutboxPoller(service);

  /** One tick is one pass. */
  @Test
  void pollRunsOnePassAndReturnsItsSummary() {
    when(service.publishDueBatch()).thenReturn(new PublishSummary(3, 2, 1));

    assertThat(poller.poll()).isEqualTo(new PublishSummary(3, 2, 1));
    verify(service).publishDueBatch();
  }

  /** A failing pass is logged and swallowed so the schedule survives. */
  @Test
  void failingPassIsContained() {
    when(service.publishDueBatch()).thenThrow(new IllegalStateException("database unavailable"));

    assertThat(poller.poll()).isEqualTo(PublishSummary.EMPTY);
  }

  /** poll() is scheduled with a fixed delay from the property on the analytics scheduler. */
  @Test
  void pollIsScheduledOnTheAnalyticsScheduler() throws NoSuchMethodException {
    Method poll = OutboxPoller.class.getMethod("poll");
    Scheduled scheduled = poll.getAnnotation(Scheduled.class);

    assertThat(scheduled).isNotNull();
    assertThat(scheduled.scheduler()).isEqualTo(OutboxSchedulingConfig.SCHEDULER_BEAN);
    assertThat(scheduled.fixedDelayString()).contains("analytics.outbox.poll-interval-ms");
    assertThat(scheduled.fixedRate()).isEqualTo(-1L);
    assertThat(scheduled.cron()).isEmpty();
  }

  /** The configuration enables scheduling and exposes the bean under the referenced name. */
  @Test
  void configurationEnablesSchedulingAndNamesTheBean() throws NoSuchMethodException {
    assertThat(OutboxSchedulingConfig.class.getAnnotation(EnableScheduling.class)).isNotNull();
    Bean bean =
        OutboxSchedulingConfig.class.getMethod("analyticsTaskScheduler").getAnnotation(Bean.class);
    assertThat(bean).isNotNull();
    assertThat(bean.name()).containsExactly("analyticsTaskScheduler");
  }

  /** Work submitted to the scheduler runs on a dedicated, named, single thread. */
  @Test
  void schedulerRunsWorkOnDedicatedAnalyticsThread() throws Exception {
    ThreadPoolTaskScheduler scheduler = new OutboxSchedulingConfig().analyticsTaskScheduler();
    scheduler.initialize();
    try {
      CompletableFuture<String> threadName = new CompletableFuture<>();
      scheduler.execute(() -> threadName.complete(Thread.currentThread().getName()));

      assertThat(threadName.get(5, TimeUnit.SECONDS))
          .startsWith(OutboxSchedulingConfig.THREAD_PREFIX);
      assertThat(threadName.get()).isNotEqualTo(Thread.currentThread().getName());
      assertThat(scheduler.getPoolSize()).isEqualTo(1);
    } finally {
      scheduler.shutdown();
    }
  }

  /** The service is mandatory. */
  @Test
  void nullServiceIsRejected() {
    assertThatThrownBy(() -> new OutboxPoller(null)).isInstanceOf(NullPointerException.class);
  }
}
