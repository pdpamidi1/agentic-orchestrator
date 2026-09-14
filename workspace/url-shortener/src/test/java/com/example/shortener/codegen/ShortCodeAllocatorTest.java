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
 */
class ShortCodeAllocatorTest {

  private static final long OFFSET = ShortenerProperties.DEFAULT_COUNTER_SEED_OFFSET;

  /** A driver-style message carrying connection details that must never be logged. */
  private static final String CONNECTION_DETAILS =
      "Unable to connect to Redis at redis://cache.internal.example:6379/0";

  private final RedisBatchCounterSource redisSource = mock(RedisBatchCounterSource.class);
  private final UrlMappingRepository repository = mock(UrlMappingRepository.class);
  private final DbSequenceCounterSource dbSource = new DbSequenceCounterSource(repository);
  private final ShortCodeAllocator allocator =
      new ShortCodeAllocator(redisSource, dbSource, OFFSET);

  private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
  private Logger allocatorLogger;

  @BeforeEach
  void setUp() {
    when(redisSource.codeSource()).thenReturn(CodeSource.REDIS);
    allocatorLogger = (Logger) LoggerFactory.getLogger(ShortCodeAllocator.class);
    logEvents.start();
    allocatorLogger.addAppender(logEvents);
  }

  @AfterEach
  void tearDown() {
    allocatorLogger.detachAppender(logEvents);
    logEvents.stop();
  }

  // --- normal path -----------------------------------------------------------------------------

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

  @Test
  void everyCodeHasAtLeastThreeCharactersEvenWithTheSmallestOffset() {
    ShortCodeAllocator minimal =
        new ShortCodeAllocator(redisSource, dbSource, Base62Codec.MIN_OFFSET_FOR_THREE_CHARS);
    when(redisSource.next()).thenReturn(0L, 1L, 61L, 62L, 3843L);

    for (int i = 0; i < 5; i++) {
      assertThat(minimal.allocate().code()).hasSizeGreaterThanOrEqualTo(3).matches("[0-9a-zA-Z]+");
    }
  }

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

  @Test
  void fallsBackToDbSequenceWhenRedisConnectionFails() {
    when(redisSource.next()).thenThrow(new RedisConnectionFailureException(CONNECTION_DETAILS));
    when(repository.nextSequenceValue()).thenReturn(238_328L);

    AllocatedCode allocated = allocator.allocate();

    assertThat(allocated.codeSource()).isEqualTo(CodeSource.DB_SEQUENCE);
    assertThat(allocated.code()).isEqualTo(Base62Codec.encode(238_328L + OFFSET));
    verify(repository).nextSequenceValue();
  }

  @Test
  void fallsBackToDbSequenceWhenRedisTimesOut() {
    when(redisSource.next()).thenThrow(new QueryTimeoutException("Redis command timed out"));
    when(repository.nextSequenceValue()).thenReturn(238_329L);

    AllocatedCode allocated = allocator.allocate();

    assertThat(allocated.codeSource()).isEqualTo(CodeSource.DB_SEQUENCE);
    assertThat(allocated.code()).isEqualTo(Base62Codec.encode(238_329L + OFFSET));
  }

  @Test
  void fallsBackOnAnyRuntimeFailureOfTheRedisSource() {
    when(redisSource.next()).thenThrow(new IllegalStateException("INCRBY returned no value"));
    when(repository.nextSequenceValue()).thenReturn(238_330L);

    assertThat(allocator.allocate().codeSource()).isEqualTo(CodeSource.DB_SEQUENCE);
  }

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

  @Test
  void propagatesFailureWhenTheFallbackFailsToo() {
    when(redisSource.next()).thenThrow(new RedisConnectionFailureException(CONNECTION_DETAILS));
    when(repository.nextSequenceValue())
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));

    assertThatThrownBy(allocator::allocate)
        .isInstanceOf(DataAccessResourceFailureException.class)
        .hasMessage("database unavailable");
  }

  @Test
  void rejectsNegativeCounterValuesInsteadOfProducingACode() {
    when(redisSource.next()).thenReturn(-1L);

    assertThatThrownBy(allocator::allocate).isInstanceOf(IllegalStateException.class);
    verify(repository, never()).nextSequenceValue();
  }

  // --- construction ----------------------------------------------------------------------------

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

  @Test
  void rejectsMissingSources() {
    assertThatNullPointerException()
        .isThrownBy(() -> new ShortCodeAllocator(null, dbSource, OFFSET));
    assertThatNullPointerException()
        .isThrownBy(() -> new ShortCodeAllocator(redisSource, null, OFFSET));
  }

  @Test
  void beanNamesMatchTheDefaultComponentNamesOfTheSources() {
    assertThat(ShortCodeAllocator.PRIMARY_SOURCE).isEqualTo("redisBatchCounterSource");
    assertThat(ShortCodeAllocator.FALLBACK_SOURCE).isEqualTo("dbSequenceCounterSource");
  }

  // --- collaborators covered here: DbSequenceCounterSource and AllocatedCode -------------------

  @Test
  void dbSequenceSourceDrawsNextvalFromUrlCodeSeq() {
    when(repository.nextSequenceValue()).thenReturn(238_328L, 238_329L);

    assertThat(dbSource.next()).isEqualTo(238_328L);
    assertThat(dbSource.next()).isEqualTo(238_329L);
    assertThat(dbSource.codeSource()).isEqualTo(CodeSource.DB_SEQUENCE);
    assertThat(DbSequenceCounterSource.SEQUENCE_NAME).isEqualTo("url_code_seq");
    verify(repository, times(2)).nextSequenceValue();
  }

  @Test
  void dbSequenceSourcePropagatesDatabaseFailures() {
    when(repository.nextSequenceValue())
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));

    assertThatThrownBy(dbSource::next).isInstanceOf(DataAccessResourceFailureException.class);
  }

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
