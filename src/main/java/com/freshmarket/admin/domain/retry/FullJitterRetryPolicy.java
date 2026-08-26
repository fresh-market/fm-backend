package com.freshmarket.admin.domain.retry;

import java.time.Duration;
import java.util.random.RandomGenerator;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * 관리자 인증 저장소 재시도에서 공통으로 사용하는 Full Jitter 백오프 정책.
 * 서비스는 재시도 예산 계산과 대기 구현을 직접 복사하지 않고 이 컴포넌트에 위임한다.
 */
@Component
public class FullJitterRetryPolicy {

    private final RandomGenerator random;
    private final LongSupplier nanoTime;
    private final LongConsumer parker;

    public FullJitterRetryPolicy() {
        this(ThreadLocalRandom.current(), System::nanoTime, LockSupport::parkNanos);
    }

    FullJitterRetryPolicy(RandomGenerator random, LongSupplier nanoTime, LongConsumer parker) {
        this.random = random;
        this.nanoTime = nanoTime;
        this.parker = parker;
    }

    public long deadline(Duration budget) {
        long now = nanoTime.getAsLong();
        long budgetNanos = budget.toNanos();
        return now > Long.MAX_VALUE - budgetNanos ? Long.MAX_VALUE : now + budgetNanos;
    }

    public boolean waitBeforeRetry(
            int retryNumber,
            long deadlineNanos,
            Duration baseDelay,
            Duration maxDelay) {
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }

        long remainingNanos = deadlineNanos - nanoTime.getAsLong();
        if (remainingNanos <= 0L) {
            return false;
        }

        long exponentialCapNanos = baseDelay.toNanos() << Math.max(0, retryNumber - 1);
        long cappedDelayNanos = Math.min(maxDelay.toNanos(), exponentialCapNanos);
        long jitterUpperBoundNanos = Math.min(cappedDelayNanos, remainingNanos);
        if (jitterUpperBoundNanos <= 0L) {
            return false;
        }

        long delayNanos = random.nextLong(jitterUpperBoundNanos + 1L);
        if (delayNanos > 0L) {
            parker.accept(delayNanos);
        }
        return !Thread.currentThread().isInterrupted() && nanoTime.getAsLong() < deadlineNanos;
    }
}