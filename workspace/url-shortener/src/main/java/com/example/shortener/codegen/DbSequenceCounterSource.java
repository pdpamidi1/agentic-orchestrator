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
 */
@Component
public class DbSequenceCounterSource implements CounterSource {

  /** Name of the Postgres sequence drawn from. */
  public static final String SEQUENCE_NAME = "url_code_seq";

  private final UrlMappingRepository repository;

  public DbSequenceCounterSource(UrlMappingRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  @Override
  public long next() {
    return repository.nextSequenceValue();
  }

  @Override
  public CodeSource codeSource() {
    return CodeSource.DB_SEQUENCE;
  }
}
