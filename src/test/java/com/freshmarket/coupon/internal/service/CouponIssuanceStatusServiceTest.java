package com.freshmarket.coupon.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import com.freshmarket.coupon.internal.cache.CachedCoupon;
import com.freshmarket.coupon.internal.cache.CouponCache;
import com.freshmarket.coupon.internal.cache.CouponIssuanceCountCache;
import com.freshmarket.coupon.internal.dto.CouponIssuanceStatusResponse;
import com.freshmarket.coupon.internal.entity.Coupon;
import com.freshmarket.coupon.internal.entity.CouponScope;
import com.freshmarket.coupon.internal.entity.DiscountType;
import com.freshmarket.coupon.internal.exception.CouponErrorCode;
import com.freshmarket.coupon.internal.exception.CouponException;
import com.freshmarket.coupon.internal.repository.CouponRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponIssuanceStatusServiceTest {

    private static final long COUPON_ID = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 12, 0);

    @Mock
    private CouponCache couponCache;

    @Mock
    private CouponIssuanceCountCache issuanceCountCache;

    @Mock
    private CouponRepository couponRepository;

    private CouponIssuanceStatusService sut;

    // CouponEventServiceTest 와 같은 이유로 @InjectMocks 대신 직접 만든다: Clock 은 mock 이 아니라
    // 고정된 실제 인스턴스라야 isLive() 판정이 시험이 기대하는 시각 위에서 선다
    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        sut = new CouponIssuanceStatusService(couponCache, issuanceCountCache, couponRepository, clock);
    }

    @Test
    void 쿠폰이_없으면_예외를_던진다() {
        // given
        when(couponCache.find(COUPON_ID)).thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> sut.findStatus(COUPON_ID))
                .isInstanceOf(CouponException.class)
                .extracting(e -> ((CouponException) e).getErrorCode())
                .isEqualTo(CouponErrorCode.COUPON_NOT_FOUND);
    }

    @Test
    void 무제한_쿠폰이면_예외를_던진다() {
        // given
        when(couponCache.find(COUPON_ID)).thenReturn(Optional.of(unlimitedCoupon()));

        // when, then
        assertThatThrownBy(() -> sut.findStatus(COUPON_ID))
                .isInstanceOf(CouponException.class)
                .extracting(e -> ((CouponException) e).getErrorCode())
                .isEqualTo(CouponErrorCode.NOT_LIMITED);
    }

    @Test
    void 레디스에_값이_있으면_그_값을_쓴다() {
        // given
        when(couponCache.find(COUPON_ID)).thenReturn(Optional.of(liveCoupon(10000)));
        when(issuanceCountCache.find(COUPON_ID)).thenReturn(Optional.of(8231));

        // when
        CouponIssuanceStatusResponse response = sut.findStatus(COUPON_ID);

        // then
        assertThat(response.totalQuantity()).isEqualTo(10000);
        assertThat(response.issuedQuantity()).isEqualTo(8231);
        assertThat(response.remaining()).isEqualTo(1769);
    }

    // 이벤트가 살아있지 않으면(오픈 전/종료 후) DB 값을 믿어도 된다 — 오픈 전엔 0이 맞고, 종료 배치가 이미 맞춘 뒤라면 정확하다
    @Test
    void 이벤트가_살아있지_않고_레디스_값이_없으면_DB_값을_쓴다() {
        // given
        when(couponCache.find(COUPON_ID)).thenReturn(Optional.of(notLiveCoupon(10000)));
        when(issuanceCountCache.find(COUPON_ID)).thenReturn(Optional.empty());
        Coupon coupon = Coupon.draftLimited("선착순 쿠폰", CouponScope.ORDER, DiscountType.AMOUNT, 1000,
                LocalDate.now(), LocalDate.now().plusDays(3),
                10000, NOW.minusDays(2), NOW.minusDays(1),
                null, null, null);
        setField(coupon, "issuedQuantity", 10000);
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));

        // when
        CouponIssuanceStatusResponse response = sut.findStatus(COUPON_ID);

        // then
        assertThat(response.issuedQuantity()).isEqualTo(10000);
    }

    /*
     * 이게 이번에 고친 핵심이다. 이벤트가 진행 중인데 Redis 를 못 읽으면, DB 의 issued_quantity
     * 는 이벤트 시작 전 값(대개 0)에 멈춰 있는 값이라 그걸 대신 보여주면 완전히 틀린 값이 될 수
     * 있다. 그래서 DB 로 메우지 않고 실패로 답해야 한다.
     */
    @Test
    void 이벤트가_살아있는데_레디스_값이_없으면_실패로_답한다() {
        // given
        when(couponCache.find(COUPON_ID)).thenReturn(Optional.of(liveCoupon(10000)));
        when(issuanceCountCache.find(COUPON_ID)).thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> sut.findStatus(COUPON_ID))
                .isInstanceOf(CouponException.class)
                .extracting(e -> ((CouponException) e).getErrorCode())
                .isEqualTo(CouponErrorCode.ISSUANCE_STATUS_UNAVAILABLE);
    }

    private static CachedCoupon unlimitedCoupon() {
        return new CachedCoupon(COUPON_ID, CouponScope.ORDER, null, null, null, null, true);
    }

    // 지금 발급 창 안이고 스위치도 켜져 있다 — isLive() 가 참이다
    private static CachedCoupon liveCoupon(int totalQuantity) {
        return new CachedCoupon(COUPON_ID, CouponScope.ORDER, totalQuantity,
                NOW.minusHours(1), NOW.plusHours(1), null, true);
    }

    // 스위치가 꺼져 있다(오픈 전 초안이거나 이미 종료됐다) — isLive() 가 거짓이다
    private static CachedCoupon notLiveCoupon(int totalQuantity) {
        return new CachedCoupon(COUPON_ID, CouponScope.ORDER, totalQuantity,
                NOW.minusDays(2), NOW.minusDays(1), null, false);
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
}
