package com.freshmarket.coupon.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import com.freshmarket.coupon.domain.cache.CachedCoupon;
import com.freshmarket.coupon.domain.cache.CouponCache;
import com.freshmarket.coupon.domain.cache.CouponIssuanceCountCache;
import com.freshmarket.coupon.domain.dto.CouponIssuanceStatusResponse;
import com.freshmarket.coupon.domain.entity.CouponScope;
import com.freshmarket.coupon.domain.exception.CouponErrorCode;
import com.freshmarket.coupon.domain.exception.CouponException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/*
 * Redis/DB 대체 로직은 CouponIssuanceCountCache 로 옮겨서 그쪽 테스트가 본다. 여기서는 이
 * 서비스가 스스로 하는 일(쿠폰 존재/선착순 여부 판정, 총 수량과 발급 수를 합쳐 응답을 만드는 것)
 * 만 본다.
 */
@ExtendWith(MockitoExtension.class)
class CouponIssuanceStatusServiceTest {

    private static final long COUPON_ID = 1L;

    @Mock
    private CouponCache couponCache;

    @Mock
    private CouponIssuanceCountCache issuanceCountCache;

    @InjectMocks
    private CouponIssuanceStatusService sut;

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
    void 총_수량과_발급_수를_합쳐_잔여를_계산한다() {
        // given
        when(couponCache.find(COUPON_ID)).thenReturn(Optional.of(limitedCoupon(10000)));
        when(issuanceCountCache.find(COUPON_ID)).thenReturn(8231);

        // when
        CouponIssuanceStatusResponse response = sut.findStatus(COUPON_ID);

        // then
        assertThat(response.totalQuantity()).isEqualTo(10000);
        assertThat(response.issuedQuantity()).isEqualTo(8231);
        assertThat(response.remaining()).isEqualTo(1769);
    }

    private static CachedCoupon unlimitedCoupon() {
        return new CachedCoupon(COUPON_ID, CouponScope.ORDER, null, null, null, null, true);
    }

    private static CachedCoupon limitedCoupon(int totalQuantity) {
        return new CachedCoupon(COUPON_ID, CouponScope.ORDER, totalQuantity,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1), null, true);
    }
}
