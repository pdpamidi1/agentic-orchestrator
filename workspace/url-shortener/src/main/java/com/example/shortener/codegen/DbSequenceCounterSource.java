/*
 * DbSequenceCounterSource.java — Fallback CounterSource backed by the url_code_seq sequence
 *
 * Layer: codegen. The CounterSource that ShortCodeAllocator turns to when the Redis batch counter
 * throws. Delegates to UrlMappingRepository.nextSequenceValue() so that PostgreSQL, which is
 * already the system of record and always required, can keep the write surface available during
 * a Redis outage (AC-11, ADR-004; docs/operations.md 1.2 and 2.3).
 */
package com.example.shortener.codegen;

import com.example.shortener.domain.CodeSource;
import com.example.shortener.domain.UrlMappingRepository;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Fallback counter backed by the Postgres sequence {@value #SEQUENCE_NAME} (AC-11, ADR-004).
 *
 * <p>Every call performs one {@code nextval('url_code_seq')} round trip; the sequence is seeded at
 * {@code 62^3} by the V1 migration so that even before the seed offset is applied a value encodes
 * to at least three base62 characters. Values are never reused; gaps are acceptable.
 *
 * <p><b>Responsibility.</b> Adapt the repository's native {@code nextval} query to the {@link
 * CounterSource} contract and label its values {@link CodeSource#DB_SEQUENCE}.
 *
 * <p><b>Invariants and guarantees.</b> Monotonic and unique across all instances because the
 * sequence lives in the shared database ({@code INCREMENT BY 1 NO CYCLE}). Sequence values are
 * non-transactional in PostgreSQL: a value drawn inside the write transaction is consumed even if
 * the subsequent insert rolls back, which is one of the accepted gap sources. The sequence's range
 * ({@code >= 238328}) overlaps the Redis range once Redis has issued that many values; the primary
 * key on {@code urls.short_code} turns such a collision into a 409 (operations 2.3).
 *
 * <p><b>Thread-safety and lifecycle.</b> Stateless singleton {@code @Component}, registered under
 * the bean name {@code dbSequenceCounterSource} that {@link ShortCodeAllocator#FALLBACK_SOURCE}
 * refers to. Safe for concurrent use; each call is an independent database statement.
 *
 * <p><b>Design choice.</b> A database sequence, rather than a second Redis or an in-memory counter,
 * was chosen because it needs no extra infrastructure, is durable and is guaranteed unique across
 * write replicas. It is not the primary because every value costs a round trip.
 */
@Component
public class DbSequenceCounterSource implements CounterSource {

  /**
   * Name of the Postgres sequence drawn from. Created by {@code V1__create_urls_table.sql} with
   * {@code START WITH 238328 INCREMENT BY 1 NO CYCLE CACHE 1}; the repository query names it
   * literally, this constant documents the coupling.
   */
  public static final String SEQUENCE_NAME = "url_code_seq";

  /** Repository exposing the native {@code nextval} query; the only collaborator. */
  private final UrlMappingRepository repository;

  /**
   * Creates the source.
   *
   * @param repository the {@code urls} repository providing {@code nextSequenceValue()}
   * @throws NullPointerException when {@code repository} is {@code null}
   */
  public DbSequenceCounterSource(UrlMappingRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  /**
   * Draws the next sequence value with one {@code SELECT nextval('url_code_seq')}.
   *
   * <p>Side effect: advances the sequence permanently. Runs in the caller's transaction if one is
   * active (the write service's), otherwise in its own.
   *
   * @return the next sequence value, {@code >= 238328}
   * @throws org.springframework.dao.DataAccessException when PostgreSQL is unavailable; the
   *     allocator propagates this to the caller since there is no further fallback
   */
  @Override
  public long next() {
    return repository.nextSequenceValue();
  }

  /**
   * Always {@link CodeSource#DB_SEQUENCE}.
   *
   * @return the wire-level origin {@code "db_sequence"}
   */
  @Override
  public CodeSource codeSource() {
    return CodeSource.DB_SEQUENCE;
  }
}
