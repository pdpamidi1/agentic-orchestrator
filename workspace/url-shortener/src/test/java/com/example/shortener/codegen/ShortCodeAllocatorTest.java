/*
 * ShortCodeAllocatorTest.java — seed-offset encoding and Redis-to-Postgres fallback of the
 * short-code allocator.
 *
 * Layer: test (unit). Pins how a raw counter value becomes a short code (raw + seed offset,
 * base62; AC-10, AMB-11), that any RuntimeException from the Redis source switches this one
 * allocation to the Postgres sequence with code_source = db_sequence (AC-11), that the switch is
 * logged once at WARN without connection details, that Redis is retried on the next allocation,
 * and the constructor guards. Also covers the small collaborators DbSequenceCounterSource and
 * AllocatedCode. Technique: JUnit 5 + Mockito (mocked RedisBatchCounterSource and
 * UrlMappingRepository) plus a Logback ListAppender to capture log events; no Spring context, no
 * containers. Run with ./mvnw test.
 */
package com.example.shortener.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.CodeSource;
import com.example.shortener.domain.UrlMappingRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * {@link ShortCodeAllocator} encodes counter values with the seed offset (AC-10, AMB-11) and falls
 * back from the (mocked) Redis source to the database sequence when Redis fails (AC-11).
 *
 * <p>Fixture strategy: the primary source is a Mockito mock of {@link RedisBatchCounterSource} (so
 * outages are simulated by {@code thenThrow}), the fallback is a real {@link
 * DbSequenceCounterSource} over a mocked {@link UrlMappingRepository}, and the allocator under test
 * uses the production default offset {@link #OFFSET}. A Logback {@link ListAppender} is attached to
 * the allocator's logger in {@link #setUp()} and detached in {@link #tearDown()} so the WARN
 * emitted on fallback can be asserted on. Everything is per test instance; nothing is shared.
 *
 * <p>Removing this class would leave the fallback decision (which lives only in the allocator)
 * untested at unit level and, in particular, the requirement that connection URIs from driver
 * messages never reach the logs; the integration test exercises the fallback end to end but does
 * not inspect log output.
 */
class ShortCodeAllocatorTest {

  /** Production default seed offset, 62^5: generated codes are six characters or more. */
  private static final long OFFSET = ShortenerProperties.DEFAULT_COUNTER_SEED_OFFSET;

  /** A driver-style message carrying connection details that must never be logged. */
  private static final String CONNECTION_DETAILS =
      "Unable to connect to Redis at redis://cache.internal.example:6379/0";

  /** Primary counter, mocked so failures and exact raw values can be scripted per test. */
  private final RedisBatchCounterSource redisSource = mock(RedisBatchCounterSource.class);

  /** Backs the real fallback source; {@code nextSequenceValue()} is stubbed per test. */
  private final UrlMappingRepository repository = mock(UrlMappingRepository.class);

  /** Real fallback source over the mocked repository (also tested directly below). */
  private final DbSequenceCounterSource dbSource = new DbSequenceCounterSource(repository);

  /** Allocator under test with the production default offset. */
  private final ShortCodeAllocator allocator =
      new ShortCodeAllocator(redisSource, dbSource, OFFSET);

  /** Captures every event logged by the allocator during a test. */
  private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();

  private Logger allocatorLogger;

  /**
   * Stubs the mocked primary to report {@link CodeSource#REDIS} (needed for the WARN message) and
   * attaches the capturing appender to the allocator's Logback logger.
   */
  @BeforeEach
  void setUp() {
    when(redisSource.codeSource()).thenReturn(CodeSource.REDIS);
    allocatorLogger = (Logger) LoggerFactory.getLogger(ShortCodeAllocator.class);
    logEvents.start();
    allocatorLogger.addAppender(logEvents);
  }

  /** Detaches the appender so events of one test never leak into another or into normal output. */
  @AfterEach
  void tearDown() {
    allocatorLogger.detachAppender(logEvents);
    logEvents.stop();
  }

  // --- normal path -----------------------------------------------------------------------------

  /**
   * Given Redis returning raw value 1, when a code is allocated, then it is base62(1 + 62^5) =
   * "100001" attributed to {@code redis}, the repository is never touched and nothing at WARN or
   * above is logged.
   */
  @Test
  void allocatesFromRedisWithOffsetAppliedAndRedisSource() {
    when(redisSource.next()).thenReturn(1L);

    AllocatedCode allocated = allocator.allocate();

    assertThat(allocated.codeSource()).isEqualTo(CodeSource.REDIS);
    assertThat(allocated.code()).isEqualTo(Base62Codec.encode(1L + OFFSET)).isEqualTo("100001");
    assertThat(Base62Codec.decode(allocated.code()) - OFFSET).isEqualTo(1L);
    verifyNoInteractions(repository);
    assertThat(logEvents.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
  }

  /**
   * Given Redis returning 10, 11, 12 in turn, when three codes are allocated, then they are
   * distinct valid base62 codes that decode back (minus the offset) to exactly those raw values.
   */
  @Test
  void consecutiveRedisValuesYieldDistinctCodes() {
    when(redisSource.next()).thenReturn(10L, 11L, 12L);

    List<String> codes =
        List.of(
            allocator.allocate().code(), allocator.allocate().code(), allocator.allocate().code());

    assertThat(codes).doesNotHaveDuplicates().allMatch(Base62Codec::isValid);
    assertThat(codes.stream().map(Base62Codec::decode).map(v -> v - OFFSET))
        .containsExactly(10L, 11L, 12L);
    verify(redisSource, times(3)).next();
    verifyNoInteractions(repository);
  }

  /**
   * Given an allocator built with the smallest permitted offset (62^2) and raw values at the one-,
   * two- and three-character boundaries, when codes are allocated, then each has at least three
   * alphanumeric characters (AMB-11).
   */
  @Test
  void everyCodeHasAtLeastThreeCharactersEvenWithTheSmallestOffset() {
    ShortCodeAllocator minimal =
        new ShortCodeAllocator(redisSource, dbSource, Base62Codec.MIN_OFFSET_FOR_THREE_CHARS);
    when(redisSource.next()).thenReturn(0L, 1L, 61L, 62L, 3843L);

    for (int i = 0; i < 5; i++) {
      assertThat(minimal.allocate().code()).hasSizeGreaterThanOrEqualTo(3).matches("[0-9a-zA-Z]+");
    }
  }

  /**
   * Given {@link ShortenerProperties} with {@code counter-seed-offset = 4000}, when the allocator
   * is built through its Spring constructor, then it reports that offset and encodes raw 5 as
   * base62(4005).
   */
  @Test
  void usesConfiguredSeedOffsetFromProperties() {
    ShortenerProperties properties =
        new ShortenerProperties("http://localhost:8080", 1000, Duration.ofHours(24), 4000L);
    ShortCodeAllocator configured = new ShortCodeAllocator(redisSource, dbSource, properties);
    when(redisSource.next()).thenReturn(5L);

    assertThat(configured.seedOffset()).isEqualTo(4000L);
    assertThat(configured.allocate().code()).isEqualTo(Base62Codec.encode(4005L));
  }

  // --- fallback path (AC-11) -------------------------------------------------------------------

  /**
   * Given Redis throwing {@link RedisConnectionFailureException} and the sequence yielding 238328,
   * when a code is allocated, then it is attributed to {@code db_sequence}, encodes the sequence
   * value plus offset, and {@code nextval} was called once (AC-11).
   */
  @Test
  void fallsBackToDbSequenceWhenRedisConnectionFails() {
    when(redisSource.next()).thenThrow(new RedisConnectionFailureException(CONNECTION_DETAILS));
    when(repository.nextSequenceValue()).thenReturn(238_328L);

    AllocatedCode allocated = allocator.allocate();

    assertThat(allocated.codeSource()).isEqualTo(CodeSource.DB_SEQUENCE);
    assertThat(allocated.code()).isEqualTo(Base62Codec.encode(238_328L + OFFSET));
    verify(repository).nextSequenceValue();
  }

  /**
   * Given Redis timing out ({@link QueryTimeoutException}), when a code is allocated, then the
   * fallback is used exactly as for a connection failure.
   */
  @Test
  void fallsBackToDbSequenceWhenRedisTimesOut() {
    when(redisSource.next()).thenThrow(new QueryTimeoutException("Redis command timed out"));
    when(repository.nextSequenceValue()).thenReturn(238_329L);

    AllocatedCode allocated = allocator.allocate();

    assertThat(allocated.codeSource()).isEqualTo(CodeSource.DB_SEQUENCE);
    assertThat(allocated.code()).isEqualTo(Base62Codec.encode(238_329L + OFFSET));
  }

  /**
   * Given the Redis source failing with a non-Redis {@link IllegalStateException} (e.g. a missing
   * INCRBY reply), when a code is allocated, then it still falls back: any RuntimeException of the
   * primary counts as an outage.
   */
  @Test
  void fallsBackOnAnyRuntimeFailureOfTheRedisSource() {
    when(redisSource.next()).thenThrow(new IllegalStateException("INCRBY returned no value"));
    when(repository.nextSequenceValue()).thenReturn(238_330L);

    assertThat(allocator.allocate().codeSource()).isEqualTo(CodeSource.DB_SEQUENCE);
  }

  /**
   * Given a Redis failure whose message embeds a connection URI, when the fallback happens, then
   * exactly one WARN is logged naming the exception class and both counter names, containing no
   * host, port or scheme from the message, carrying no throwable, and nothing is logged at ERROR.
   */
  @Test
  void degradationIsLoggedOnceAsWarningWithoutConnectionDetails() {
    when(redisSource.next()).thenThrow(new RedisConnectionFailureException(CONNECTION_DETAILS));
    when(repository.nextSequenceValue()).thenReturn(238_331L);

    allocator.allocate();

    List<ILoggingEvent> warnings =
        logEvents.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    assertThat(warnings).hasSize(1);
    ILoggingEvent warning = warnings.getFirst();
    assertThat(warning.getFormattedMessage())
        .contains("RedisConnectionFailureException")
        .contains(CodeSource.REDIS.wireValue())
        .contains(CodeSource.DB_SEQUENCE.wireValue())
        .doesNotContain(CONNECTION_DETAILS)
        .doesNotContain("cache.internal.example")
        .doesNotContain("6379")
        .doesNotContain("redis://");
    assertThat(warning.getThrowableProxy()).isNull();
    assertThat(logEvents.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.ERROR));
  }

  /**
   * Given Redis failing once and then returning 7, when two codes are allocated, then the first
   * comes from the sequence and the second from Redis again: recovery is automatic, no circuit
   * stays open.
   */
  @Test
  void retriesRedisOnTheNextAllocationAfterAFailure() {
    when(redisSource.next())
        .thenThrow(new RedisConnectionFailureException(CONNECTION_DETAILS))
        .thenReturn(7L);
    when(repository.nextSequenceValue()).thenReturn(238_332L);

    AllocatedCode first = allocator.allocate();
    AllocatedCode second = allocator.allocate();

    assertThat(first.codeSource()).isEqualTo(CodeSource.DB_SEQUENCE);
    assertThat(second.codeSource()).isEqualTo(CodeSource.REDIS);
    assertThat(second.code()).isEqualTo(Base62Codec.encode(7L + OFFSET));
    verify(redisSource, times(2)).next();
    verify(repository, times(1)).nextSequenceValue();
  }

  /**
   * Given both Redis and the database failing, when a code is allocated, then the database
   * exception is propagated unchanged to the caller (which the handler turns into a 500).
   */
  @Test
  void propagatesFailureWhenTheFallbackFailsToo() {
    when(redisSource.next()).thenThrow(new RedisConnectionFailureException(CONNECTION_DETAILS));
    when(repository.nextSequenceValue())
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));

    assertThatThrownBy(allocator::allocate)
        .isInstanceOf(DataAccessResourceFailureException.class)
        .hasMessage("database unavailable");
  }

  /**
   * Given Redis returning -1 (a corrupt counter), when a code is allocated, then an {@link
   * IllegalStateException} is thrown rather than a code produced, and the fallback is not consulted
   * (a bad value is not an outage).
   */
  @Test
  void rejectsNegativeCounterValuesInsteadOfProducingACode() {
    when(redisSource.next()).thenReturn(-1L);

    assertThatThrownBy(allocator::allocate).isInstanceOf(IllegalStateException.class);
    verify(repository, never()).nextSequenceValue();
  }

  // --- construction ----------------------------------------------------------------------------

  /**
   * Given a seed offset of 0 or of 62^2 - 1, when constructing, then the allocator is rejected with
   * a message naming the minimum 3844 (AMB-11 must hold by construction).
   */
  @Test
  void rejectsSeedOffsetsThatCouldProduceCodesShorterThanThreeCharacters() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ShortCodeAllocator(redisSource, dbSource, 0L))
        .withMessageContaining("3844");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new ShortCodeAllocator(
                    redisSource, dbSource, Base62Codec.MIN_OFFSET_FOR_THREE_CHARS - 1));
  }

  /** Given a {@code null} primary or fallback source, when constructing, then an NPE is thrown. */
  @Test
  void rejectsMissingSources() {
    assertThatNullPointerException()
        .isThrownBy(() -> new ShortCodeAllocator(null, dbSource, OFFSET));
    assertThatNullPointerException()
        .isThrownBy(() -> new ShortCodeAllocator(redisSource, null, OFFSET));
  }

  /**
   * Given the qualifier constants, when compared with Spring's default bean names for the two
   * {@code @Component} sources, then they match, so the {@code @Qualifier} wiring resolves.
   */
  @Test
  void beanNamesMatchTheDefaultComponentNamesOfTheSources() {
    assertThat(ShortCodeAllocator.PRIMARY_SOURCE).isEqualTo("redisBatchCounterSource");
    assertThat(ShortCodeAllocator.FALLBACK_SOURCE).isEqualTo("dbSequenceCounterSource");
  }

  // --- collaborators covered here: DbSequenceCounterSource and AllocatedCode -------------------

  /**
   * Given the repository yielding two sequence values, when the DB source is drawn twice, then it
   * returns them in order, reports {@code db_sequence}, names {@code url_code_seq} and delegated
   * once per call.
   */
  @Test
  void dbSequenceSourceDrawsNextvalFromUrlCodeSeq() {
    when(repository.nextSequenceValue()).thenReturn(238_328L, 238_329L);

    assertThat(dbSource.next()).isEqualTo(238_328L);
    assertThat(dbSource.next()).isEqualTo(238_329L);
    assertThat(dbSource.codeSource()).isEqualTo(CodeSource.DB_SEQUENCE);
    assertThat(DbSequenceCounterSource.SEQUENCE_NAME).isEqualTo("url_code_seq");
    verify(repository, times(2)).nextSequenceValue();
  }

  /**
   * Given the repository failing, when the DB source is drawn, then the data-access exception is
   * propagated unchanged (no fallback exists below the sequence).
   */
  @Test
  void dbSequenceSourcePropagatesDatabaseFailures() {
    when(repository.nextSequenceValue())
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));

    assertThatThrownBy(dbSource::next).isInstanceOf(DataAccessResourceFailureException.class);
  }

  /**
   * Given the {@link AllocatedCode} record, when built with equal components it is equal, and when
   * built with a {@code null} code, a {@code null} source or a blank code it is rejected.
   */
  @Test
  void allocatedCodeRejectsMissingOrBlankComponents() {
    assertThat(new AllocatedCode("abc", CodeSource.REDIS))
        .isEqualTo(new AllocatedCode("abc", CodeSource.REDIS));
    assertThatNullPointerException().isThrownBy(() -> new AllocatedCode(null, CodeSource.REDIS));
    assertThatNullPointerException().isThrownBy(() -> new AllocatedCode("abc", null));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new AllocatedCode("  ", CodeSource.DB_SEQUENCE));
  }
}
