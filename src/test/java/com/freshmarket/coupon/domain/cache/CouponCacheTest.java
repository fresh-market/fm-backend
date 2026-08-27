package com.freshmarket.coupon.domain.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.freshmarket.coupon.domain.entity.Coupon;
import com.freshmarket.coupon.domain.entity.CouponScope;
import com.freshmarket.coupon.domain.entity.DiscountType;
import com.freshmarket.coupon.domain.issue.CouponIssueProperties;
import com.freshmarket.coupon.domain.repository.CouponRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponCacheTest {

    private static final long COUPON_ID = 77L;
    private static final Duration TTL = Duration.ofSeconds(5);

    @Mock
    private CouponRepository couponRepository;

    private MovableClock clock;
    private CouponCache sut;

    @BeforeEach
    void setUp() {
        clock = new MovableClock(Instant.parse("2026-06-01T12:00:00Z"));
        CouponIssueProperties properties = new CouponIssueProperties(
                Duration.ofSeconds(60), Duration.ofMillis(20), 500, 1, 10_000,
                Duration.ofSeconds(2), TTL);
        sut = new CouponCache(couponRepository, properties, clock);
    }

    @Test
    void 처음_찾으면_DB_를_읽는다() {
        // given
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon(true)));

        // when
        Optional<CachedCoupon> found = sut.find(COUPON_ID);

        // then
        assertThat(found).isPresent();
        verify(couponRepository).findById(COUPON_ID);
    }

    // 켜진 쿠폰만 담는다. 발급 창 안에서는 그 값들이 얼어붙으므로 다시 읽을 이유가 없다
    @Test
    void 켜진_쿠폰은_두_번째부터_DB_를_안_읽는다() {
        // given
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon(true)));

        // when
        sut.find(COUPON_ID);
        sut.find(COUPON_ID);
        sut.find(COUPON_ID);

        // then
        verify(couponRepository, times(1)).findById(COUPON_ID);
    }

    /*
     * 이 규칙이 이 클래스의 핵심이다.
     * 꺼진 값을 담으면 관리자가 여는 순간부터 TTL 만큼 이 인스턴스가 "지금은 발급할 수 없다" 로
     * 답한다. 이벤트가 열리는 바로 그 순간에 사람이 가장 많이 몰리므로 그 창을 만들면 안 된다.
     */
    @Test
    void 꺼진_쿠폰은_매번_DB_를_읽는다() {
        // given
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon(false)));

        // when
        sut.find(COUPON_ID);
        sut.find(COUPON_ID);
        sut.find(COUPON_ID);

        // then
        verify(couponRepository, times(3)).findById(COUPON_ID);
    }

    @Test
    void 없는_쿠폰은_담지_않는다() {
        // given
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.empty());

        // when
        assertThat(sut.find(COUPON_ID)).isEmpty();
        assertThat(sut.find(COUPON_ID)).isEmpty();

        // then
        verify(couponRepository, times(2)).findById(COUPON_ID);
    }

    // TTL 이 곧 이 앱이 마감을 넘겨 요청을 받아 주는 시간이다
    @Test
    void TTL_이_지나면_다시_읽는다() {
        // given
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon(true)));
        sut.find(COUPON_ID);

        // when
        clock.advance(TTL.plusMillis(1));
        sut.find(COUPON_ID);

        // then
        verify(couponRepository, times(2)).findById(COUPON_ID);
    }

    @Test
    void TTL_안에서는_다시_읽지_않는다() {
        // given
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon(true)));
        sut.find(COUPON_ID);

        // when
        clock.advance(TTL.minusMillis(1));
        sut.find(COUPON_ID);

        // then
        verify(couponRepository, times(1)).findById(COUPON_ID);
    }

    // 관리자가 이벤트를 열고 닫은 뒤에 부른다. 그 인스턴스만은 곧바로 새 값을 본다
    @Test
    void 비우면_다시_읽는다() {
        // given
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon(true)));
        sut.find(COUPON_ID);

        // when
        sut.evict(COUPON_ID);
        sut.find(COUPON_ID);

        // then
        verify(couponRepository, times(2)).findById(COUPON_ID);
    }

    /*
     * 이것 때문에 직접 만든 것을 Caffeine 으로 바꿨다.
     * 캐시가 빈 순간에 여러 요청 스레드가 몰려도 DB 를 읽는 것은 하나뿐이고 나머지는 그 결과를
     * 함께 기다린다. TTL 마다 한 번씩 오는 몰림이 인스턴스 수만큼 곱해지는 것을 없앤다.
     */
    @Test
    void 동시에_몰려도_DB_는_한_번만_읽는다() throws Exception {
        // given 읽기가 느리게 끝나도록 붙잡아 둔다
        CountDownLatch 읽기를_붙잡는다 = new CountDownLatch(1);
        when(couponRepository.findById(COUPON_ID)).thenAnswer(invocation -> {
            읽기를_붙잡는다.await();
            return Optional.of(coupon(true));
        });

        // when 스무 스레드가 같은 쿠폰을 동시에 찾는다
        int 요청_수 = 20;
        CountDownLatch 다_들어왔다 = new CountDownLatch(요청_수);
        CountDownLatch 다_끝났다 = new CountDownLatch(요청_수);
        try (ExecutorService 스레드들 = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 요청_수; i++) {
                스레드들.submit(() -> {
                    다_들어왔다.countDown();
                    sut.find(COUPON_ID);
                    다_끝났다.countDown();
                });
            }
            assertThat(다_들어왔다.await(5, TimeUnit.SECONDS)).isTrue();
            읽기를_붙잡는다.countDown();
            assertThat(다_끝났다.await(5, TimeUnit.SECONDS)).isTrue();
        }

        // then
        verify(couponRepository, times(1)).findById(COUPON_ID);
    }

    @Test
    void 담은_값이_엔티티와_같다() {
        // given
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon(true)));

        // when
        CachedCoupon cached = sut.find(COUPON_ID).orElseThrow();

        // then
        assertThat(cached.couponId()).isEqualTo(COUPON_ID);
        assertThat(cached.scope()).isEqualTo(CouponScope.ORDER);
        assertThat(cached.totalQuantity()).isEqualTo(100);
        assertThat(cached.active()).isTrue();
        assertThat(cached.isLimited()).isTrue();
    }

    private static Coupon coupon(boolean active) {
        Coupon coupon = Coupon.draftLimited("선착순 쿠폰", CouponScope.ORDER, DiscountType.AMOUNT, 1000,
                LocalDate.of(2026, 1, 1), LocalDate.of(2030, 1, 1),
                100, LocalDateTime.of(2026, 5, 1, 0, 0), LocalDateTime.of(2026, 7, 1, 0, 0));
        setField(coupon, "id", COUPON_ID);
        setField(coupon, "active", active);
        return coupon;
    }

    private static void setField(Object target, String name, Object value) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                // 상위 클래스에 있을 수 있다. BaseMutableTimeEntity 가 id 를 갖는다
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(name + " 을 심지 못했다", e);
            }
        }
        throw new IllegalStateException(name + " 필드가 없다");
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
