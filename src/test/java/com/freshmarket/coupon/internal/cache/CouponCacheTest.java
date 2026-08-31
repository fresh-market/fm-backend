package com.freshmarket.coupon.internal.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

import com.freshmarket.coupon.internal.entity.Coupon;
import com.freshmarket.coupon.internal.entity.CouponScope;
import com.freshmarket.coupon.internal.entity.DiscountType;
import com.freshmarket.coupon.internal.issue.CouponIssueProperties;
import com.freshmarket.coupon.internal.repository.CouponRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
class CouponCacheTest {

    private static final long COUPON_ID = 77L;

    // 마감 없는 쿠폰에만 쓰이는 대비값이다. 선착순 쿠폰은 마감에서 계산된 시각에 죽는다
    private static final Duration FALLBACK_TTL = Duration.ofSeconds(5);

    private static final LocalDateTime ISSUE_END_AT = LocalDateTime.of(2026, 7, 1, 0, 0);

    // CouponSeqInitializer 가 Redis 키에 거는 꼬리와 같은 값이다
    private static final Duration TTL_TAIL = Duration.ofSeconds(60);

    @Mock
    private CouponRepository couponRepository;

    private MovableClock clock;
    private ExecutorService loader;
    private CouponCache sut;

    @BeforeEach
    void setUp() {
        clock = new MovableClock(Instant.parse("2026-06-01T12:00:00Z"));
        CouponIssueProperties properties = new CouponIssueProperties(
                Duration.ofSeconds(60), Duration.ofMillis(20), 500, 1, 10_000,
                Duration.ofSeconds(2), Duration.ofSeconds(3), FALLBACK_TTL);
        /*
         * 실행기를 단일 스레드로 준다.
         * AsyncCache 는 future 가 완료될 때 쓰기 시각을 찍는데 그 콜백이 이 실행기에서 돈다.
         * 한 스레드라 순서가 정해지고, 아래 기록이_끝나기를_기다린다() 로 그 시점을 잡을 수 있다.
         *
         * 같은 스레드(Runnable::run)로 두면 안 된다. 로딩이 Caffeine 의 모니터 안에서 돌아
         * 이 클래스가 피하려는 핀이 그대로 생기고, 동시 시험이 교착한다.
         */
        loader = Executors.newSingleThreadExecutor();
        sut = new CouponCache(couponRepository, properties, clock, loader);
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

    /*
     * 스냅샷이 Redis 키와 같은 시각에 죽는지 본다.
     * 그 시각을 넘겨 들고 있으면 이 캐시가 이미 사라진 이벤트를 설명하게 된다.
     */
    @Test
    void 마감에서_60초가_지나면_다시_읽는다() throws Exception {
        // given
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon(true)));
        sut.find(COUPON_ID);
        기록이_끝나기를_기다린다();

        // when
        clock.advance(키가_죽기까지().plusMillis(1));
        sut.find(COUPON_ID);

        // then
        verify(couponRepository, times(2)).findById(COUPON_ID);
    }

    // 발급 창 안에서는 값이 얼어붙으므로 그 시각 전에는 다시 읽을 이유가 없다
    @Test
    void 마감에서_60초가_되기_전에는_다시_읽지_않는다() throws Exception {
        // given
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon(true)));
        sut.find(COUPON_ID);
        기록이_끝나기를_기다린다();

        // when
        clock.advance(키가_죽기까지().minusMillis(1));
        sut.find(COUPON_ID);

        // then
        verify(couponRepository, times(1)).findById(COUPON_ID);
    }

    /*
     * 읽어도 만료 시각이 밀리지 않는지 본다.
     * expireAfterRead 가 남은 시간 대신 새 기간을 주면 요청이 계속 오는 동안 스냅샷이 안 죽어서,
     * 이벤트가 끝난 뒤에도 이 인스턴스가 옛 값으로 답한다.
     */
    @Test
    void 중간에_읽어도_만료_시각이_밀리지_않는다() throws Exception {
        // given
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon(true)));
        sut.find(COUPON_ID);
        기록이_끝나기를_기다린다();

        // when 절반쯤에서 한 번 읽고, 원래 만료 시각을 넘긴다
        Duration 남은_시간 = 키가_죽기까지();
        clock.advance(남은_시간.dividedBy(2));
        sut.find(COUPON_ID);
        clock.advance(남은_시간.dividedBy(2).plusMillis(1));
        sut.find(COUPON_ID);

        // then
        verify(couponRepository, times(2)).findById(COUPON_ID);
    }

    /*
     * 마감이 없는 쿠폰은 맞출 기준이 없어 설정값으로 죽는다.
     * 그 쿠폰은 Redis 키에도 만료가 안 걸리므로 여기서마저 기준을 잃으면 스냅샷이 안 죽는다.
     */
    @Test
    void 마감이_없는_쿠폰은_설정값으로_죽는다() throws Exception {
        // given
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(마감_없는_쿠폰()));
        sut.find(COUPON_ID);
        기록이_끝나기를_기다린다();

        // when
        clock.advance(FALLBACK_TTL.plusMillis(1));
        sut.find(COUPON_ID);

        // then
        verify(couponRepository, times(2)).findById(COUPON_ID);
    }

    // 스냅샷이 살아 있는 시간이다. 이 시험이 이 식을 들고 있어야 캐시 쪽 식과 대조가 된다
    private Duration 키가_죽기까지() {
        return Duration.between(LocalDateTime.now(clock), ISSUE_END_AT.plus(TTL_TAIL));
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

    /*
     * 캐시가 CompletableFuture 의 포장을 벗겨 내보내는지 본다.
     * 안 벗기면 호출자의 catch(DataAccessException) 이 CompletionException 에 가려 안 걸린다.
     */
    @Test
    void DB_실패가_포장_없이_그대로_나온다() {
        // given
        when(couponRepository.findById(COUPON_ID)).thenThrow(new QueryTimeoutException("DB 가 답하지 않는다"));

        // when, then
        assertThatThrownBy(() -> sut.find(COUPON_ID))
                .isInstanceOf(QueryTimeoutException.class);
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

    /*
     * 캐시가 쓰기 시각을 찍는 콜백이 끝나기를 기다린다.
     * find() 의 join() 은 값이 채워지면 돌아오지만 Caffeine 의 뒷정리는 실행기에 남아 있을 수 있다.
     * 단일 스레드라 이 빈 작업이 도는 시점이면 앞의 것들이 다 끝난 뒤다.
     */
    private void 기록이_끝나기를_기다린다() throws Exception {
        loader.submit(() -> {
        }).get(5, TimeUnit.SECONDS);
    }

    private static Coupon coupon(boolean active) {
        Coupon coupon = Coupon.draftLimited("선착순 쿠폰", CouponScope.ORDER, DiscountType.AMOUNT, 1000,
                LocalDate.of(2026, 1, 1), LocalDate.of(2030, 1, 1),
                100, LocalDateTime.of(2026, 5, 1, 0, 0), ISSUE_END_AT,
                null, null, null);
        setField(coupon, "id", COUPON_ID);
        setField(coupon, "active", active);
        return coupon;
    }

    private static Coupon 마감_없는_쿠폰() {
        Coupon coupon = Coupon.draftUnlimited("상시 쿠폰", CouponScope.ORDER, DiscountType.AMOUNT, 1000,
                LocalDate.of(2026, 1, 1), LocalDate.of(2030, 1, 1),
                null, null, null);
        setField(coupon, "id", COUPON_ID);
        setField(coupon, "active", true);
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
