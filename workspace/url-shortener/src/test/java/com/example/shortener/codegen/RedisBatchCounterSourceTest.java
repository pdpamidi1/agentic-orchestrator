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
 */
class RedisBatchCounterSourceTest {

  private static final int BATCH = 1000;

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> ops = mock(ValueOperations.class);

  /** Simulates the shared Redis key: INCRBY is atomic and returns the value after increment. */
  private final AtomicLong redisKey = new AtomicLong();

  @BeforeEach
  void setUp() {
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.increment(eq(RedisBatchCounterSource.COUNTER_KEY), anyLong()))
        .thenAnswer(invocation -> redisKey.addAndGet(invocation.<Long>getArgument(1)));
  }

  private RedisBatchCounterSource source() {
    return new RedisBatchCounterSource(redis, BATCH);
  }

  // --- batching --------------------------------------------------------------------------------

  @Test
  void firstAllocationReservesOneBatchAndStartsAtTheBeginningOfTheRange() {
    RedisBatchCounterSource source = source();

    long first = source.next();

    assertThat(first).isEqualTo(1L);
    verify(ops, times(1)).increment(RedisBatchCounterSource.COUNTER_KEY, (long) BATCH);
    assertThat(redisKey.get()).isEqualTo(BATCH);
  }

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

  @Test
  void keyNameAndCodeSourceAreFixed() {
    assertThat(RedisBatchCounterSource.COUNTER_KEY).isEqualTo("shortener:counter");
    assertThat(source().codeSource()).isEqualTo(CodeSource.REDIS);
  }

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

  @Test
  void smallBatchSizesAreHonoured() {
    RedisBatchCounterSource source = new RedisBatchCounterSource(redis, 1);

    assertThat(List.of(source.next(), source.next(), source.next())).containsExactly(1L, 2L, 3L);
    verify(ops, times(3)).increment(RedisBatchCounterSource.COUNTER_KEY, 1L);
  }

  @Test
  void rejectsNonPositiveBatchSize() {
    assertThatIllegalArgumentException().isThrownBy(() -> new RedisBatchCounterSource(redis, 0));
    assertThatIllegalArgumentException().isThrownBy(() -> new RedisBatchCounterSource(redis, -5));
  }

  // --- restart gaps (AMB-10) -------------------------------------------------------------------

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

  @Test
  void missingReplyIsReportedAsIllegalState() {
    when(ops.increment(eq(RedisBatchCounterSource.COUNTER_KEY), anyLong())).thenReturn(null);

    assertThatThrownBy(source()::next)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(RedisBatchCounterSource.COUNTER_KEY);
  }

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

  @Test
  void counterBelowOneIsRejected() {
    redisKey.set(-2L * BATCH);

    assertThatThrownBy(source()::next)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("below the valid range");
  }

  // --- concurrency -----------------------------------------------------------------------------

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
