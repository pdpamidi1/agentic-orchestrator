/*
 * ClickEventRecorder.java — writes one click_outbox row per successful redirect
 *
 * Layer: analytics.recording. The transactional-outbox producer side of click analytics
 * (AC-1, AC-2, AC-3, AC-10, AC-11, AC-14): hashes the client address (IpHasher), reduces the
 * Referer to its host (ReferrerHostExtractor) and inserts a PENDING ClickOutboxEntry with a fresh
 * random UUID idempotency key inside a database transaction that is committed before the redirect
 * response leaves the server. Performs no Kafka or other network I/O; the relay (analytics.outbox)
 * publishes asynchronously. This package must never import analytics.kafka or analytics.outbox
 * (ArchitectureTest rule).
 */
package com.example.shortener.analytics.recording;

import com.example.shortener.analytics.domain.ClickOutboxEntry;
import com.example.shortener.analytics.repository.ClickOutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a successful redirect as one {@code click_outbox} row (AC-1, AC-2, AC-3, AC-10, AC-11,
 * AC-14).
 *
 * <p><b>Responsibility.</b> Build and persist exactly one {@link ClickOutboxEntry} per call: {@code
 * idempotency_key} = a random UUID generated here (per redirect, never derived from the request),
 * {@code short_code} = the redirected code, {@code occurred_at} = {@code clock.instant()} (UTC),
 * {@code hashed_ip} = {@link IpHasher#hash}, {@code referrer_host} = {@link
 * ReferrerHostExtractor#extract}. The raw client address is consumed by the hasher on the first
 * line and is never stored, logged, or placed in an exception message.
 *
 * <p><b>Transaction.</b> {@link #record} is {@link Transactional}: it opens the request's write
 * transaction (or joins an outer one) and the insert is committed when the method returns, before
 * the controller writes the {@code 302}. On any failure the transaction is rolled back and the
 * exception propagates; the caller (the redirect controller) is responsible for containing it so
 * that the redirect itself is unaffected (AC-3). Nothing here blocks on Kafka, Redis or the
 * network: the only I/O is the single {@code INSERT}.
 *
 * <p><b>Thread-safety and lifecycle.</b> Immutable singleton bean; the repository, hasher,
 * extractor and clock are thread-safe.
 */
@Service
public class ClickEventRecorder {

  /** DEBUG-only tracing; log lines carry the short code and key, never an address or referrer. */
  private static final Logger log = LoggerFactory.getLogger(ClickEventRecorder.class);

  /** Target of the single insert. */
  private final ClickOutboxRepository repository;

  /** Pseudonymises the client address. */
  private final IpHasher ipHasher;

  /** Reduces the Referer header to its host. */
  private final ReferrerHostExtractor referrerHostExtractor;

  /** Source of {@code occurred_at} / {@code created_at}; fixed in tests, UTC system clock live. */
  private final Clock clock;

  /**
   * Creates the recorder with the system UTC clock (the constructor Spring uses).
   *
   * @param repository the {@code click_outbox} repository
   * @param ipHasher the salted address hasher
   * @param referrerHostExtractor the Referer host extractor
   * @throws NullPointerException when any collaborator is {@code null}
   */
  @Autowired
  public ClickEventRecorder(
      ClickOutboxRepository repository,
      IpHasher ipHasher,
      ReferrerHostExtractor referrerHostExtractor) {
    this(repository, ipHasher, referrerHostExtractor, Clock.systemUTC());
  }

  /**
   * Creates the recorder with an explicit clock.
   *
   * @param repository the {@code click_outbox} repository
   * @param ipHasher the salted address hasher
   * @param referrerHostExtractor the Referer host extractor
   * @param clock source of the click timestamp
   * @throws NullPointerException when any argument is {@code null}
   */
  public ClickEventRecorder(
      ClickOutboxRepository repository,
      IpHasher ipHasher,
      ReferrerHostExtractor referrerHostExtractor,
      Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.ipHasher = Objects.requireNonNull(ipHasher, "ipHasher");
    this.referrerHostExtractor =
        Objects.requireNonNull(referrerHostExtractor, "referrerHostExtractor");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Inserts one pending outbox row for a redirect that has just been resolved successfully.
   *
   * <p>Must be called only after the short code resolved to a live target: unknown or expired codes
   * never reach this method, so they leave no row and no event (AC-2).
   *
   * @param shortCode the redirected short code
   * @param clientIp raw peer address from the request; hashed immediately, never retained
   * @param refererHeader raw {@code Referer} header, or {@code null} when absent
   * @return the persisted entry (status {@code PENDING}, fresh idempotency key)
   * @throws NullPointerException when {@code shortCode} is {@code null}
   * @throws RuntimeException any persistence failure, after the transaction was rolled back
   */
  @Transactional
  public ClickOutboxEntry record(String shortCode, String clientIp, String refererHeader) {
    Objects.requireNonNull(shortCode, "shortCode");
    String hashedIp = ipHasher.hash(clientIp);
    String referrerHost = referrerHostExtractor.extract(refererHeader);
    Instant now = clock.instant();
    ClickOutboxEntry entry =
        new ClickOutboxEntry(
            UUID.randomUUID().toString(), shortCode, now, hashedIp, referrerHost, now);
    ClickOutboxEntry saved = repository.save(entry);
    log.debug(
        "Recorded click for short code '{}' with idempotency key {}",
        shortCode,
        saved.getIdempotencyKey());
    return saved;
  }
}
