/*
 * ProcessedClickEventRepository.java — Spring Data JPA repository for processed_click_event rows
 *
 * Layer: analytics.repository (depends on analytics.domain only). Gives the Kafka consumer an
 * atomic "claim this idempotency key" primitive so redelivered events are applied at most once
 * (AC-6, AC-12).
 */
package com.example.shortener.analytics.repository;

import com.example.shortener.analytics.domain.ProcessedClickEvent;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link ProcessedClickEvent}.
 *
 * <p><b>Usage.</b> The consumer calls {@link #insertIfAbsent} first, in the same transaction as the
 * aggregate updates; a return value of {@code 0} means the key was already processed and the event
 * must be skipped. Singleton proxy bean; safe for concurrent use.
 */
public interface ProcessedClickEventRepository extends JpaRepository<ProcessedClickEvent, String> {

  /**
   * Records an idempotency key as processed unless it already is ({@code ON CONFLICT DO NOTHING}).
   *
   * @param idempotencyKey the event's key
   * @param processedAt when the event is being applied (UTC)
   * @return {@code 1} when the key was newly inserted, {@code 0} when it was already present
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "INSERT INTO processed_click_event (idempotency_key, processed_at)"
              + " VALUES (:idempotencyKey, :processedAt)"
              + " ON CONFLICT (idempotency_key) DO NOTHING",
      nativeQuery = true)
  int insertIfAbsent(
      @Param("idempotencyKey") String idempotencyKey, @Param("processedAt") Instant processedAt);

  /**
   * Retention purge of dedupe markers older than {@code cutoff}. Must run inside a transaction.
   *
   * @param cutoff rows with {@code processed_at < cutoff} are removed
   * @return number of deleted rows
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM ProcessedClickEvent p WHERE p.processedAt < :cutoff")
  int deleteProcessedBefore(@Param("cutoff") Instant cutoff);
}
