/*
 * RedisBatchCounterSourceTest.java — batched Redis INCRBY counter semantics.
 *
 * Layer: test (unit). Pins the primary short-code counter (AC-10, AMB-10, ADR-002): one INCRBY of
 * batch size per reserved range, contiguous strictly increasing values handed out locally, restart
 * gaps instead of reuse, disjoint ranges for instances sharing the key, propagation of Redis
 * failures, rejection of a counter that moved backwards, and uniqueness under 16 concurrent
 * threads. Technique: JUnit 5 + Mockito; StringRedisTemplate/ValueOperations are mocked and the
 * shared Redis key is simulated by an AtomicLong, so no Redis is needed. Run with ./mvnw test.
 */
package com.example.shortener.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.CodeSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@link RedisBatchCounterSource} reserves ranges with one {@code INCRBY} per batch and hands out
 * strictly increasing, unique values, also under concurrent allocation (AC-10, AMB-10).
 *
 * <p>Fixture strategy: a mocked {@link StringRedisTemplate} whose {@code opsForValue().increment}
 * is answered by an {@link AtomicLong} standing in for the shared Redis key ({@link #setUp()}), so
 * {@code INCRBY} behaves atomically and returns the post-increment value exactly like Redis. Every
 * test gets a fresh mock and a fresh key (JUnit's per-method instance), and constructs the source
 * itself through {@link #source()} so several instances can share one simulated key.
 *
 * <p>Removing this class would leave the batching arithmetic (range start = reply - batch + 1), the
 * "one INCRBY per batch" cost model, restart-gap behaviour and the protection against a reset
 * counter unverified; the integration tests run with a batch size of one and cannot observe any of
 * these.
 */
class RedisBatchCounterSourceTest {

  /** Batch size under test: the production default and the value named in AC-10 (INCRBY 1000). */
  private static final int BATCH = 1000;

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  /** Mocked value operations returned by {@code redis.opsForValue()}. */
  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> ops = mock(ValueOperations.class);

  /** Simulates the shared Redis key: INCRBY is atomic and returns the value after increment. */
  private final AtomicLong redisKey = new AtomicLong();

  /**
   * Wires the mocks: {@code opsForValue()} yields {@link #ops}, and {@code increment(COUNTER_KEY,
   * n)} atomically adds {@code n} to {@link #redisKey} and returns the new value.
   */
  @BeforeEach
  void setUp() {
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.increment(eq(RedisBatchCounterSource.COUNTER_KEY), anyLong()))
        .thenAnswer(invocation -> redisKey.addAndGet(invocation.<Long>getArgument(1)));
  }

  /**
   * Creates a source with the default {@link #BATCH} over the shared mock.
   *
   * @return a fresh source; several of them model several application instances
   */
  private RedisBatchCounterSource source() {
    return new RedisBatchCounterSource(redis, BATCH);
  }

  // --- batching --------------------------------------------------------------------------------

  /**
   * Given a fresh source and a counter at 0, when the first value is drawn, then exactly one {@code
   * INCRBY 1000} is issued, the key becomes 1000 and the value handed out is 1 (the start of the
   * reserved range, not its end).
   */
  @Test
  void firstAllocationReservesOneBatchAndStartsAtTheBeginningOfTheRange() {
    RedisBatchCounterSource source = source();

    long first = source.next();

    assertThat(first).isEqualTo(1L);
    verify(ops, times(1)).increment(RedisBatchCounterSource.COUNTER_KEY, (long) BATCH);
    assertThat(redisKey.get()).isEqualTo(BATCH);
  }

  /**
   * Given a fresh source, when exactly {@link #BATCH} values are drawn, then they are 1..1000 in
   * order and Redis was contacted only once.
   */
  @Test
  void wholeBatchIsServedWithExactlyOneIncrby() {
    RedisBatchCounterSource source = source();

    List<Long> values = new ArrayList<>();
    for (int i = 0; i < BATCH; i++) {
      values.add(source.next());
    }

    assertThat(values).containsExactlyElementsOf(LongStream.rangeClosed(1, BATCH).boxed().toList());
    verify(ops, times(1)).increment(eq(RedisBatchCounterSource.COUNTER_KEY), anyLong());
  }

  /**
   * Given a single source, when 3 * BATCH + 1 values are drawn, then the sequence is contiguous
   * from 1 to 3001 and exactly four {@code INCRBY} calls were made (one per range boundary).
   */
  @Test
  void exhaustingTheRangeTriggersTheNextIncrbyAndValuesStayContiguous() {
    RedisBatchCounterSource source = source();

    List<Long> values = new ArrayList<>();
    for (int i = 0; i < 3 * BATCH + 1; i++) {
      values.add(source.next());
    }

    assertThat(values)
        .containsExactlyElementsOf(LongStream.rangeClosed(1, 3L * BATCH + 1).boxed().toList());
    verify(ops, times(4)).increment(RedisBatchCounterSource.COUNTER_KEY, (long) BATCH);
  }

  /**
   * Given the source, when its constants are inspected, then the global key is {@code
   * shortener:counter} (shared by every instance; see docs/operations.md) and codes it produces are
   * attributed to {@link CodeSource#REDIS}.
   */
  @Test
  void keyNameAndCodeSourceAreFixed() {
    assertThat(RedisBatchCounterSource.COUNTER_KEY).isEqualTo("shortener:counter");
    assertThat(source().codeSource()).isEqualTo(CodeSource.REDIS);
  }

  /**
   * Given a source built through the Spring constructor from default {@link ShortenerProperties},
   * when it reserves a range, then the batch size is 1000 and the {@code INCRBY} uses it.
   */
  @Test
  void batchSizeDefaultsToOneThousandFromProperties() {
    ShortenerProperties properties =
        new ShortenerProperties(
            ShortenerProperties.DEFAULT_BASE_URL,
            ShortenerProperties.DEFAULT_COUNTER_BATCH_SIZE,
            Duration.ofHours(24),
            ShortenerProperties.DEFAULT_COUNTER_SEED_OFFSET);
    RedisBatchCounterSource source = new RedisBatchCounterSource(redis, properties);

    assertThat(source.batchSize()).isEqualTo(1000);
    source.next();
    verify(ops).increment(RedisBatchCounterSource.COUNTER_KEY, 1000L);
  }

  /**
   * Given a batch size of one (the integration-test setting), when three values are drawn, then
   * they are 1, 2, 3 and each required its own {@code INCRBY 1}.
   */
  @Test
  void smallBatchSizesAreHonoured() {
    RedisBatchCounterSource source = new RedisBatchCounterSource(redis, 1);

    assertThat(List.of(source.next(), source.next(), source.next())).containsExactly(1L, 2L, 3L);
    verify(ops, times(3)).increment(RedisBatchCounterSource.COUNTER_KEY, 1L);
  }

  /** Given a batch size of 0 or negative, when constructing, then the source is rejected. */
  @Test
  void rejectsNonPositiveBatchSize() {
    assertThatIllegalArgumentException().isThrownBy(() -> new RedisBatchCounterSource(redis, 0));
    assertThatIllegalArgumentException().isThrownBy(() -> new RedisBatchCounterSource(redis, -5));
  }

  // --- restart gaps (AMB-10) -------------------------------------------------------------------

  /**
   * Given an instance that consumed 10 of its 1000 reserved values, when a new instance (a restart)
   * draws its first value, then it receives 1001: the 990 unused values are lost, never reissued
   * (AMB-10).
   */
  @Test
  void aNewInstanceNeverReusesValuesOfAPartiallyConsumedRange() {
    RedisBatchCounterSource beforeRestart = source();
    for (int i = 0; i < 10; i++) {
      beforeRestart.next();
    }

    RedisBatchCounterSource afterRestart = source();
    long firstAfterRestart = afterRestart.next();

    // The 990 unused values of the first range are lost; the new range starts right after it.
    assertThat(firstAfterRestart).isEqualTo(BATCH + 1L);
    verify(ops, times(2)).increment(RedisBatchCounterSource.COUNTER_KEY, (long) BATCH);
  }

  /**
   * Given two instances sharing the same key, when both allocate a full batch interleaved, then one
   * owns 1..1000 and the other 1001..2000 with no overlap (uniqueness across instances).
   */
  @Test
  void twoInstancesSharingTheKeyReceiveDisjointRanges() {
    RedisBatchCounterSource a = source();
    RedisBatchCounterSource b = source();

    long fromA = a.next();
    long fromB = b.next();

    assertThat(fromA).isEqualTo(1L);
    assertThat(fromB).isEqualTo(BATCH + 1L);
    for (int i = 1; i < BATCH; i++) {
      assertThat(a.next()).isEqualTo(1L + i);
      assertThat(b.next()).isEqualTo(BATCH + 1L + i);
    }
  }

  // --- failure propagation ---------------------------------------------------------------------

  /**
   * Given Redis refusing the first {@code INCRBY}, when {@code next()} is called twice, then the
   * first call propagates the {@link RedisConnectionFailureException} unchanged (the allocator
   * decides about fallback) and the second call retries the {@code INCRBY} and succeeds with 1.
   */
  @Test
  void redisFailurePropagatesAndTheNextCallRetriesTheIncrby() {
    when(ops.increment(eq(RedisBatchCounterSource.COUNTER_KEY), anyLong()))
        .thenThrow(new RedisConnectionFailureException("connection refused"))
        .thenAnswer(invocation -> redisKey.addAndGet(invocation.<Long>getArgument(1)));
    RedisBatchCounterSource source = source();

    assertThatThrownBy(source::next).isInstanceOf(RedisConnectionFailureException.class);
    assertThat(source.next()).isEqualTo(1L);
    verify(ops, times(2)).increment(RedisBatchCounterSource.COUNTER_KEY, (long) BATCH);
  }

  /**
   * Given {@code increment} returning {@code null} (a pipelined/transactional template), when a
   * range is reserved, then an {@link IllegalStateException} naming the key is thrown.
   */
  @Test
  void missingReplyIsReportedAsIllegalState() {
    when(ops.increment(eq(RedisBatchCounterSource.COUNTER_KEY), anyLong())).thenReturn(null);

    assertThatThrownBy(source()::next)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(RedisBatchCounterSource.COUNTER_KEY);
  }

  /**
   * Given a source that already handed out 1..1000 and a key reset to 0 (FLUSHALL / key loss), when
   * the next range is reserved, then the returned range 1..1000 overlaps what was issued and the
   * source refuses with "moved backwards" instead of reusing values.
   */
  @Test
  void counterThatMovedBackwardsIsRejectedInsteadOfReusingValues() {
    RedisBatchCounterSource source = source();
    for (int i = 0; i < BATCH; i++) {
      source.next();
    }
    // Simulate a FLUSHALL / key reset between batches.
    redisKey.set(0);

    assertThatThrownBy(source::next)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("moved backwards");
  }

  /**
   * Given a key holding a negative value, when a range is reserved, then the range would start
   * below 1 and the source refuses with "below the valid range".
   */
  @Test
  void counterBelowOneIsRejected() {
    redisKey.set(-2L * BATCH);

    assertThatThrownBy(source()::next)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("below the valid range");
  }

  // --- concurrency -----------------------------------------------------------------------------

  /**
   * Given 16 threads released simultaneously by a latch, each drawing 500 values from one source
   * (8000 values = exactly 8 batches), when all finish, then no value was handed out twice, each
   * thread saw strictly increasing values, the union is exactly 1..8000, and Redis received exactly
   * 8 {@code INCRBY} calls and nothing else. The 30 s / 10 s timeouts only bound a deadlock; the
   * test normally completes in milliseconds.
   */
  @Test
  void concurrentAllocationYieldsUniqueStrictlyIncreasingValuesWithOneIncrbyPerBatch()
      throws Exception {
    int threads = 16;
    int perThread = 500; // 8000 values => exactly 8 batches of 1000
    RedisBatchCounterSource source = source();
    CountDownLatch start = new CountDownLatch(1);
    ConcurrentHashMap.KeySetView<Long, Boolean> seen = ConcurrentHashMap.newKeySet();
    List<Future<List<Long>>> results = new ArrayList<>();

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      for (int t = 0; t < threads; t++) {
        results.add(
            pool.submit(
                () -> {
                  List<Long> mine = new ArrayList<>(perThread);
                  start.await();
                  for (int i = 0; i < perThread; i++) {
                    long value = source.next();
                    assertThat(seen.add(value)).as("value %d handed out twice", value).isTrue();
                    mine.add(value);
                  }
                  return mine;
                }));
      }
      start.countDown();
      for (Future<List<Long>> result : results) {
        List<Long> mine = result.get(30, TimeUnit.SECONDS);
        assertThat(mine).hasSize(perThread);
        for (int i = 1; i < mine.size(); i++) {
          assertThat(mine.get(i)).isGreaterThan(mine.get(i - 1));
        }
      }
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    int total = threads * perThread;
    assertThat(seen).hasSize(total);
    assertThat(seen)
        .containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1, total).boxed().toList());
    verify(redis, times(total / BATCH)).opsForValue();
    verify(ops, times(total / BATCH)).increment(RedisBatchCounterSource.COUNTER_KEY, (long) BATCH);
    verifyNoMoreInteractions(ops);
  }
}
