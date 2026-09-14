package com.example.shortener.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Spring Data JPA repository for {@link UrlMapping} rows plus the fallback counter sequence. */
public interface UrlMappingRepository extends JpaRepository<UrlMapping, String> {

  /** Looks up a mapping by its short code (primary key). */
  Optional<UrlMapping> findByShortCode(String shortCode);

  /** Whether a mapping (generated code or custom alias) already occupies the given short code. */
  boolean existsByShortCode(String shortCode);

  /**
   * Draws the next value from the Postgres fallback counter {@code url_code_seq} (AC-11). Values
   * are never reused; gaps are acceptable.
   */
  @Query(value = "SELECT nextval('url_code_seq')", nativeQuery = true)
  long nextSequenceValue();
}
