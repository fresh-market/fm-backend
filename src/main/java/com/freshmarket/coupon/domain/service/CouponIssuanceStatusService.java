package com.freshmarket.coupon.domain.service;

import com.freshmarket.coupon.domain.cache.CachedCoupon;
import com.freshmarket.coupon.domain.cache.CouponCache;
import com.freshmarket.coupon.domain.cache.CouponIssuanceCountCache;
import com.freshmarket.coupon.domain.dto.CouponIssuanceStatusResponse;
import com.freshmarket.coupon.domain.exception.CouponErrorCode;
import com.freshmarket.coupon.domain.exception.CouponException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 선착순 쿠폰의 발급 현황(총 수량/발급 수/잔여)을 회원에게 보여준다.
 *
 * <p>총 수량은 {@link CouponCache}에서, 실시간 발급 수는 {@link CouponIssuanceCountCache}에서
 * 읽는다({@code docs/api/coupon.md} "발급 현황"). 둘 다 DB를 직접 안 때려서 발급 경로와 자원을
 * 안 다툰다. Redis 장애나 카운터 부재에 대한 DB 대체는 {@link CouponIssuanceCountCache}가 맡는다.
 */
@Service
@RequiredArgsConstructor
public class CouponIssuanceStatusService {

    private final CouponCache couponCache;
    private final CouponIssuanceCountCache issuanceCountCache;

    /**
     * @throws CouponException 그 쿠폰이 없거나(COUPON_NOT_FOUND), 선착순 대상이 아니면(NOT_LIMITED)
     */
    public CouponIssuanceStatusResponse findStatus(long couponId) {
        CachedCoupon coupon = couponCache.find(couponId)
                .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_NOT_FOUND));
        if (!coupon.isLimited()) {
            throw new CouponException(CouponErrorCode.NOT_LIMITED);
        }
        int totalQuantity = coupon.totalQuantity();
        int issuedQuantity = issuanceCountCache.find(couponId);
        return new CouponIssuanceStatusResponse(totalQuantity, issuedQuantity, totalQuantity - issuedQuantity);
    }
}
