/*
 * ClickOutboxRepository.java — Spring Data JPA repository for click_outbox rows
 *
 * Layer: analytics.repository (depends on analytics.domain only). Used by the recording path to
 * insert entries, by the outbox relay to claim batches with SELECT ... FOR UPDATE SKIP LOCKED and
 * by the retention job to purge old rows (AC-1, AC-6, AC-7, AC-10, AC-12).
 */
package com.example.shortener.analytics.repository;

import com.example.shortener.analytics.domain.ClickOutboxEntry;
import com.example.shortener.analytics.domain.OutboxStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link ClickOutboxEntry}.
 *
 * <p><b>Responsibility.</b> CRUD on {@code click_outbox} plus the two set-oriented operations the
 * relay and the retention job need: {@link #claimBatch} and {@link #deleteOccurredBefore}.
 *
 * <p><b>Thread-safety and lifecycle.</b> Singleton proxy bean created by Spring Data; safe for
 * concurrent use. {@link #claimBatch} must be called inside a transaction that stays open while the
 * claimed rows are processed and updated, otherwise the row locks are released immediately.
 */
public interface ClickOutboxRepository extends JpaRepository<ClickOutboxEntry, Long> {

  /**
   * Looks up an entry by its unique idempotency key.
   *
   * @param idempotencyKey the key
   * @return the entry, or empty
   */
  Optional<ClickOutboxEntry> findByIdempotencyKey(String idempotencyKey);

  /**
   * Whether an entry with the given idempotency key already exists (fast pre-check; the unique
   * constraint {@code uq_click_outbox_idempotency_key} is the actual guarantee).
   *
   * @param idempotencyKey the key
   * @return {@code true} when present
   */
  boolean existsByIdempotencyKey(String idempotencyKey);

  /**
   * Claims up to {@code limit} pending entries whose {@code next_attempt_at} is not after {@code
   * now}, oldest due first, locking them for the calling transaction and skipping rows already
   * locked by another relay instance ({@code FOR UPDATE SKIP LOCKED}). Concurrent relays therefore
   * never claim the same row twice and never block each other.
   *
   * <p>Native SQL because JPQL has neither {@code LIMIT} nor {@code SKIP LOCKED}. Uses {@code
   * idx_click_outbox_status_next_attempt_at}.
   *
   * @param now the reference instant (UTC)
   * @param limit maximum number of rows to claim ({@code analytics.outbox.batch-size})
   * @return at most {@code limit} locked entries, possibly empty
   */
  @Query(
      value =
          "SELECT * FROM click_outbox"
              + " WHERE status = 'PENDING' AND next_attempt_at <= :now"
              + " ORDER BY next_attempt_at, id"
              + " LIMIT :limit"
              + " FOR UPDATE SKIP LOCKED",
      nativeQuery = true)
  List<ClickOutboxEntry> claimBatch(@Param("now") Instant now, @Param("limit") int limit);

  /**
   * Counts entries in a given state (operational visibility, tests).
   *
   * @param status the state
   * @return number of rows
   */
  long countByStatus(OutboxStatus status);

  /**
   * Retention purge: deletes entries whose click occurred before {@code cutoff}, regardless of
   * state. Uses {@code idx_click_outbox_occurred_at}. Must run inside a transaction.
   *
   * @param cutoff rows with {@code occurred_at < cutoff} are removed
   * @return number of deleted rows
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM ClickOutboxEntry e WHERE e.occurredAt < :cutoff")
  int deleteOccurredBefore(@Param("cutoff") Instant cutoff);
}
