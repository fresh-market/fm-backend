package com.freshmarket.coupon.internal.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.freshmarket.coupon.internal.redis.CouponSeqAllocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
class CouponIssuanceCountCacheTest {

    private static final long COUPON_ID = 77L;
    private static final Duration TTL = Duration.ofSeconds(1);

    @Mock
    private CouponSeqAllocator seqAllocator;

    private MovableClock clock;
    private ExecutorService loader;
    private CouponIssuanceCountCache sut;

    @BeforeEach
    void setUp() {
        clock = new MovableClock(Instant.parse("2026-06-01T12:00:00Z"));
        // CouponCacheTest 와 같은 이유로 단일 스레드로 준다: 쓰기 시각이 찍히는 순서를 시험이 잡을 수 있게
        loader = Executors.newSingleThreadExecutor();
        sut = new CouponIssuanceCountCache(seqAllocator, clock, loader);
    }

    @AfterEach
    void tearDown() {
        loader.shutdownNow();
    }

    @Test
    void 처음_찾으면_레디스를_읽는다() {
        // given
        when(seqAllocator.currentIssuedCount(COUPON_ID)).thenReturn(Optional.of(8231));

        // when
        Optional<Integer> found = sut.find(COUPON_ID);

        // then
        assertThat(found).contains(8231);
        verify(seqAllocator).currentIssuedCount(COUPON_ID);
    }

    @Test
    void TTL_안에서는_다시_읽지_않는다() throws Exception {
        // given
        when(seqAllocator.currentIssuedCount(COUPON_ID)).thenReturn(Optional.of(8231));
        sut.find(COUPON_ID);
        기록이_끝나기를_기다린다();

        // when
        clock.advance(TTL.minusMillis(1));
        sut.find(COUPON_ID);

        // then
        verify(seqAllocator, times(1)).currentIssuedCount(COUPON_ID);
    }

    @Test
    void TTL_이_지나면_다시_읽는다() throws Exception {
        // given
        when(seqAllocator.currentIssuedCount(COUPON_ID)).thenReturn(Optional.of(8231));
        sut.find(COUPON_ID);
        기록이_끝나기를_기다린다();

        // when
        clock.advance(TTL.plusMillis(1));
        sut.find(COUPON_ID);

        // then
        verify(seqAllocator, times(2)).currentIssuedCount(COUPON_ID);
    }

    // 카운터가 없다(이벤트 미오픈/종료 후). 이 값을 DB로 메워도 되는지는 이 클래스가 모르므로 빈 값만 준다
    @Test
    void 카운터가_없으면_빈_값이다() {
        // given
        when(seqAllocator.currentIssuedCount(COUPON_ID)).thenReturn(Optional.empty());

        // when
        Optional<Integer> found = sut.find(COUPON_ID);

        // then
        assertThat(found).isEmpty();
    }

    // Redis 가 일시적으로 실패해도 이 클래스는 실패로 답하지 않고 빈 값으로 흡수한다
    @Test
    void 레디스가_일시적으로_실패하면_빈_값이다() {
        // given
        when(seqAllocator.currentIssuedCount(COUPON_ID))
                .thenThrow(new QueryTimeoutException("Redis 가 응답하지 않는다"));

        // when
        Optional<Integer> found = sut.find(COUPON_ID);

        // then
        assertThat(found).isEmpty();
    }

    // 일시적이지 않은 실패(코드가 잘못 부른 경우 등)까지 빈 값으로 덮으면 진짜 버그가 캐시에 묻힌다
    @Test
    void 레디스_실패가_일시적이지_않으면_그대로_던진다() {
        // given
        when(seqAllocator.currentIssuedCount(COUPON_ID))
                .thenThrow(new InvalidDataAccessApiUsageException("잘못된 호출"));

        // when, then
        assertThatThrownBy(() -> sut.find(COUPON_ID))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);
    }

    /*
     * 캐시가 값을 채우는 콜백은 전용 실행기(위의 loader, 단일 스레드)에서 돈다.
     * 그 완료를 기다려야 TTL 경계를 재는 다음 단계가 정확한 시각 위에서 선다.
     */
    private void 기록이_끝나기를_기다린다() throws Exception {
        loader.submit(() -> {
        }).get(5, TimeUnit.SECONDS);
    }

    // Clock.fixed 는 못 움직여서 TTL 경계를 못 잰다. 시험이 시간을 밀 수 있어야 한다
    private static final class MovableClock extends Clock {

        private Instant instant;

        private MovableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
