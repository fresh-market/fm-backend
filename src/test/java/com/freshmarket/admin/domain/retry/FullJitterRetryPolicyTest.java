package com.freshmarket.admin.domain.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.random.RandomGenerator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class FullJitterRetryPolicyTest {

    @Test
    void deadline은_현재시각에_예산을_더한다() {
        LongSupplier nanoTime = () -> 1_000L;
        FullJitterRetryPolicy policy = new FullJitterRetryPolicy(
                mock(RandomGenerator.class), nanoTime, ignored -> {});

        long deadline = policy.deadline(Duration.ofNanos(500));

        assertThat(deadline).isEqualTo(1_500L);
    }

    @Test
    void deadline_계산이_long_범위를_넘으면_MAX_VALUE로_제한한다() {
        LongSupplier nanoTime = () -> Long.MAX_VALUE - 10L;
        FullJitterRetryPolicy policy = new FullJitterRetryPolicy(
                mock(RandomGenerator.class), nanoTime, ignored -> {});

        long deadline = policy.deadline(Duration.ofNanos(20));

        assertThat(deadline).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void 예산이_이미_소진됐으면_대기하지_않고_false를_반환한다() {
        RandomGenerator random = mock(RandomGenerator.class);
        LongConsumer parker = mock(LongConsumer.class);
        LongSupplier nanoTime = () -> 2_000L;
        FullJitterRetryPolicy policy = new FullJitterRetryPolicy(random, nanoTime, parker);

        boolean result = policy.waitBeforeRetry(
                1, 2_000L, Duration.ofNanos(100), Duration.ofNanos(500));

        assertThat(result).isFalse();
        verify(random, never()).nextLong(org.mockito.ArgumentMatchers.anyLong());
        verify(parker, never()).accept(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void jitter가_0이면_실제_park를_호출하지_않는다() {
        RandomGenerator random = mock(RandomGenerator.class);
        LongConsumer parker = mock(LongConsumer.class);
        AtomicLong now = new AtomicLong(1_000L);
        when(random.nextLong(101L)).thenReturn(0L);
        FullJitterRetryPolicy policy = new FullJitterRetryPolicy(random, now::get, parker);

        boolean result = policy.waitBeforeRetry(
                1, 2_000L, Duration.ofNanos(100), Duration.ofNanos(500));

        assertThat(result).isTrue();
        verify(random).nextLong(101L);
        verify(parker, never()).accept(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void 뽑힌_jitter만큼_대기한_뒤_예산이_남으면_true를_반환한다() {
        RandomGenerator random = mock(RandomGenerator.class);
        AtomicLong now = new AtomicLong(1_000L);
        LongConsumer parker = delay -> now.addAndGet(delay);
        when(random.nextLong(101L)).thenReturn(40L);
        FullJitterRetryPolicy policy = new FullJitterRetryPolicy(random, now::get, parker);

        boolean result = policy.waitBeforeRetry(
                1, 2_000L, Duration.ofNanos(100), Duration.ofNanos(500));

        assertThat(result).isTrue();
        assertThat(now.get()).isEqualTo(1_040L);
        verify(random).nextLong(101L);
    }
}