/*
 * OutboxSchedulingConfig.java — dedicated scheduler for the analytics background jobs
 *
 * Layer: analytics.outbox. Switches on @Scheduled processing and defines the single-threaded
 * "analyticsTaskScheduler" that OutboxPoller (and the retention job) run on, so that relay work
 * never executes on a Tomcat request thread and cannot delay a redirect (AC-3; ArchitectureTest
 * rule on @Scheduled methods).
 */
package com.example.shortener.analytics.outbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Registers the dedicated analytics {@link ThreadPoolTaskScheduler}.
 *
 * <p>One thread is enough: the relay processes one batch per tick with a fixed delay between ticks,
 * so ticks never overlap and the single instance never claims the same row twice within a process.
 * On shutdown the scheduler waits briefly for the running tick so a half-processed batch commits or
 * rolls back cleanly.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class OutboxSchedulingConfig {

  /** Bean name of the analytics scheduler, referenced from {@code @Scheduled(scheduler = ...)}. */
  public static final String SCHEDULER_BEAN = "analyticsTaskScheduler";

  /** Thread name prefix of the scheduler thread. */
  public static final String THREAD_PREFIX = "analytics-scheduler-";

  /**
   * The scheduler every analytics {@code @Scheduled} method must name.
   *
   * @return a single-threaded scheduler with named threads
   */
  @Bean(name = SCHEDULER_BEAN)
  public ThreadPoolTaskScheduler analyticsTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix(THREAD_PREFIX);
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(30);
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }
}
