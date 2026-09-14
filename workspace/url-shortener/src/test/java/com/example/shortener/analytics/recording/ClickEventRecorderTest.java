/*
 * ClickEventRecorderTest.java — one PENDING click_outbox row per successful redirect
 *
 * Layer: test (unit). Pins, with a Mockito ClickOutboxRepository, a real IpHasher and extractor
 * and a fixed clock: exactly one save per call; the row carries the short code, occurred_at =
 * created_at = clock.instant(), hashed_ip = hasher.hash(ip), referrer_host = extractor result,
 * status PENDING; the idempotency key is a fresh random UUID per call; the raw address is nowhere
 * in the entity; record() is @Transactional; persistence failures propagate (containment is the
 * controller's job) (AC-1, AC-2, AC-3, AC-10, AC-11, AC-14). No Spring context.
 */
package com.example.shortener.analytics.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.shortener.analytics.domain.ClickOutboxEntry;
import com.example.shortener.analytics.domain.OutboxStatus;
import com.example.shortener.analytics.repository.ClickOutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.annotation.Transactional;

/** Unit tests of {@link ClickEventRecorder}. */
class ClickEventRecorderTest {

  private static final String SALT = "unit-test-salt-0123456789";
  private static final String CODE = "promo2024";
  private static final String IP = "203.0.113.7";
  private static final Instant NOW = Instant.parse("2026-09-14T10:15:30Z");

  private final ClickOutboxRepository repository = mock(ClickOutboxRepository.class);
  private final IpHasher hasher = new IpHasher(SALT);
  private final ReferrerHostExtractor extractor = new ReferrerHostExtractor();
  private final ClickEventRecorder recorder =
      new ClickEventRecorder(repository, hasher, extractor, Clock.fixed(NOW, ZoneOffset.UTC));

  /** Exactly one row is saved and it carries the mapped, pseudonymised fields. */
  @Test
  void recordsExactlyOnePendingRowWithHashedIpAndReferrerHost() {
    when(repository.save(any(ClickOutboxEntry.class))).thenAnswer(inv -> inv.getArgument(0));

    ClickOutboxEntry saved = recorder.record(CODE, IP, "https://news.example.org/a?b=1");

    ArgumentCaptor<ClickOutboxEntry> captor = ArgumentCaptor.forClass(ClickOutboxEntry.class);
    verify(repository, times(1)).save(captor.capture());
    verifyNoMoreInteractions(repository);
    ClickOutboxEntry entry = captor.getValue();
    assertThat(saved).isSameAs(entry);
    assertThat(entry.getShortCode()).isEqualTo(CODE);
    assertThat(entry.getOccurredAt()).isEqualTo(NOW);
    assertThat(entry.getCreatedAt()).isEqualTo(NOW);
    assertThat(entry.getNextAttemptAt()).isEqualTo(NOW);
    assertThat(entry.getHashedIp()).isEqualTo(hasher.hash(IP)).matches("^[0-9a-f]{64}$");
    assertThat(entry.getReferrerHost()).isEqualTo("news.example.org");
    assertThat(entry.getStatus()).isEqualTo(OutboxStatus.PENDING);
    assertThat(entry.getAttempts()).isZero();
    assertThat(entry.getPublishedAt()).isNull();
  }

  /** The idempotency key is a random UUID, different for every redirect. */
  @Test
  void idempotencyKeyIsAFreshRandomUuidPerCall() {
    when(repository.save(any(ClickOutboxEntry.class))).thenAnswer(inv -> inv.getArgument(0));

    ClickOutboxEntry first = recorder.record(CODE, IP, null);
    ClickOutboxEntry second = recorder.record(CODE, IP, null);

    assertThat(first.getIdempotencyKey()).isNotEqualTo(second.getIdempotencyKey());
    assertThat(UUID.fromString(first.getIdempotencyKey()).toString())
        .isEqualTo(first.getIdempotencyKey());
    assertThat(UUID.fromString(second.getIdempotencyKey()).toString())
        .isEqualTo(second.getIdempotencyKey());
    verify(repository, times(2)).save(any(ClickOutboxEntry.class));
  }

  /** Missing or garbage Referer gives a null referrer_host; the row is still written. */
  @Test
  void missingOrGarbageRefererYieldsNullHost() {
    when(repository.save(any(ClickOutboxEntry.class))).thenAnswer(inv -> inv.getArgument(0));

    assertThat(recorder.record(CODE, IP, null).getReferrerHost()).isNull();
    assertThat(recorder.record(CODE, IP, "").getReferrerHost()).isNull();
    assertThat(recorder.record(CODE, IP, "not a url at all").getReferrerHost()).isNull();
    verify(repository, times(3)).save(any(ClickOutboxEntry.class));
  }

  /** The raw address appears nowhere in the persisted entity; a null address still records. */
  @Test
  void rawIpIsNeverStored() {
    when(repository.save(any(ClickOutboxEntry.class))).thenAnswer(inv -> inv.getArgument(0));

    ClickOutboxEntry entry = recorder.record(CODE, IP, "https://example.com/");
    assertThat(entry.getHashedIp()).doesNotContain(IP);
    assertThat(entry.toString()).doesNotContain(IP);

    ClickOutboxEntry withoutAddress = recorder.record(CODE, null, null);
    assertThat(withoutAddress.getHashedIp()).isEqualTo(hasher.hash(null));
  }

  /** Persistence failures propagate unchanged so the caller can contain them. */
  @Test
  void persistenceFailurePropagates() {
    when(repository.save(any(ClickOutboxEntry.class)))
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));

    assertThatThrownBy(() -> recorder.record(CODE, IP, null))
        .isInstanceOf(DataAccessResourceFailureException.class);
  }

  /** A null short code is a programming error and is rejected before any I/O. */
  @Test
  void nullShortCodeIsRejected() {
    assertThatThrownBy(() -> recorder.record(null, IP, null))
        .isInstanceOf(NullPointerException.class);
    verifyNoMoreInteractions(repository);
  }

  /** The insert runs inside a Spring-managed transaction committed before the 302 is written. */
  @Test
  void recordIsTransactional() throws NoSuchMethodException {
    Transactional annotation =
        ClickEventRecorder.class
            .getMethod("record", String.class, String.class, String.class)
            .getAnnotation(Transactional.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.readOnly()).isFalse();
  }

  /** Every collaborator is mandatory. */
  @Test
  void nullCollaboratorsAreRejected() {
    assertThatThrownBy(() -> new ClickEventRecorder(null, hasher, extractor))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ClickEventRecorder(repository, null, extractor))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ClickEventRecorder(repository, hasher, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ClickEventRecorder(repository, hasher, extractor, null))
        .isInstanceOf(NullPointerException.class);
  }
}
