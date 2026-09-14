/*
 * UrlMappingRepository.java — Spring Data JPA repository for urls rows and the fallback sequence
 *
 * Layer: domain. The only database access point of the application. UrlReadService uses
 * findByShortCode, UrlWriteService uses existsByShortCode and saveAndFlush (inherited), and
 * DbSequenceCounterSource uses nextSequenceValue for the Redis-outage fallback. Spring Data
 * generates the implementation at start-up; there is no hand-written SQL apart from the native
 * nextval query (AC-3, AC-7, AC-11; ADR-004).
 */
package com.example.shortener.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data JPA repository for {@link UrlMapping} rows plus the fallback counter sequence.
 *
 * <p><b>Responsibility.</b> CRUD on {@code urls} keyed by {@code short_code} (via {@link
 * JpaRepository}) and one non-entity operation, drawing from {@code url_code_seq}, which is placed
 * here so that the codegen layer needs no second data-access technology.
 *
 * <p><b>Thread-safety and lifecycle.</b> Singleton proxy bean created by Spring Data; safe for
 * concurrent use. Methods run in the caller's transaction when one is active, otherwise each in its
 * own read-only ({@code find*}, {@code exists*}) or default transaction.
 *
 * <p><b>Uniqueness.</b> {@link #existsByShortCode} is only a fast pre-check; the primary key {@code
 * pk_urls} is the actual guarantee, and the write service treats a constraint violation on flush as
 * the same 409.
 */
public interface UrlMappingRepository extends JpaRepository<UrlMapping, String> {

  /**
   * Looks up a mapping by its short code (primary key). Equivalent to {@code findById} but named
   * for the domain; case-sensitive like the column.
   *
   * @param shortCode the short code from the request path
   * @return the mapping, or empty when no row has that key
   */
  Optional<UrlMapping> findByShortCode(String shortCode);

  /**
   * Whether a mapping (generated code or custom alias) already occupies the given short code.
   * Issues a {@code SELECT ... EXISTS}-style query without loading the row.
   *
   * @param shortCode the candidate short code
   * @return {@code true} when a row with that key exists
   */
  boolean existsByShortCode(String shortCode);

  /**
   * Draws the next value from the Postgres fallback counter {@code url_code_seq} (AC-11). Values
   * are never reused; gaps are acceptable.
   *
   * <p>Native query because JPQL has no sequence access. PostgreSQL sequences are
   * non-transactional: the value is consumed even if the surrounding transaction rolls back.
   *
   * @return the next sequence value, starting at {@code 238328} ({@code 62^3}) on a fresh schema
   * @throws org.springframework.dao.DataAccessException when the database is unavailable
   */
  @Query(value = "SELECT nextval('url_code_seq')", nativeQuery = true)
  long nextSequenceValue();
}
