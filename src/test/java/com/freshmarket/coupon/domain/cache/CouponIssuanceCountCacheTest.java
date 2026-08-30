package com.freshmarket.coupon.domain.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.freshmarket.coupon.domain.entity.Coupon;
import com.freshmarket.coupon.domain.entity.CouponScope;
import com.freshmarket.coupon.domain.entity.DiscountType;
import com.freshmarket.coupon.domain.redis.CouponSeqAllocator;
import com.freshmarket.coupon.domain.repository.CouponRepository;
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

    @Mock
    private CouponRepository couponRepository;

    private MovableClock clock;
    private ExecutorService loader;
    private CouponIssuanceCountCache sut;

    @BeforeEach
    void setUp() {
        clock = new MovableClock(Instant.parse("2026-06-01T12:00:00Z"));
        // CouponCacheTest 와 같은 이유로 단일 스레드로 준다: 쓰기 시각이 찍히는 순서를 시험이 잡을 수 있게
        loader = Executors.newSingleThreadExecutor();
        sut = new CouponIssuanceCountCache(seqAllocator, couponRepository, clock, loader);
    }

    @Test
    void 처음_찾으면_레디스를_읽는다() {
        // given
        when(seqAllocator.currentIssuedCount(COUPON_ID)).thenReturn(Optional.of(8231));

        // when
        int found = sut.find(COUPON_ID);

        // then
        assertThat(found).isEqualTo(8231);
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

    // 이벤트가 열린 적 없거나 이미 닫혀 카운터가 없으면 DB의 issued_quantity로 대신한다
    @Test
    void 레디스_카운터가_없으면_DB_값으로_대신한다() {
        // given
        when(seqAllocator.currentIssuedCount(COUPON_ID)).thenReturn(Optional.empty());
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(couponWithIssuedQuantity(10000)));

        // when
        int found = sut.find(COUPON_ID);

        // then
        assertThat(found).isEqualTo(10000);
    }

    // Redis 가 죽었어도 발급 경로는 멀쩡하므로 이 조회는 실패로 답하지 않고 DB 값으로 대신한다
    @Test
    void 레디스가_일시적으로_실패해도_DB_값으로_대신한다() {
        // given
        when(seqAllocator.currentIssuedCount(COUPON_ID))
                .thenThrow(new QueryTimeoutException("Redis 가 응답하지 않는다"));
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(couponWithIssuedQuantity(8231)));

        // when
        int found = sut.find(COUPON_ID);

        // then
        assertThat(found).isEqualTo(8231);
    }

    // 일시적이지 않은 실패(코드가 잘못 부른 경우 등)까지 덮으면 진짜 버그가 캐시에 묻힌다
    @Test
    void 레디스_실패가_일시적이지_않으면_그대로_던진다() {
        // given
        when(seqAllocator.currentIssuedCount(COUPON_ID))
                .thenThrow(new InvalidDataAccessApiUsageException("잘못된 호출"));

        // when, then
        assertThatThrownBy(() -> sut.find(COUPON_ID))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);
    }

    private static Coupon couponWithIssuedQuantity(int issuedQuantity) {
        Coupon coupon = Coupon.draftLimited("선착순 쿠폰", CouponScope.ORDER, DiscountType.AMOUNT, 1000,
                LocalDate.now(), LocalDate.now().plusDays(3),
                issuedQuantity, LocalDateTime.now().minusDays(1), LocalDateTime.now().minusHours(1));
        setField(coupon, "issuedQuantity", issuedQuantity);
        return coupon;
    }

    // CouponCacheTest 등과 같은 이유다: 팩터리가 안 받는 필드(내부가 결정하는 값)를 시험용으로만 강제로 채운다
    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
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
