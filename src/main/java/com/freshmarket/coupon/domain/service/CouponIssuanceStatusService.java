package com.freshmarket.coupon.domain.service;

import com.freshmarket.coupon.domain.cache.CachedCoupon;
import com.freshmarket.coupon.domain.cache.CouponCache;
import com.freshmarket.coupon.domain.dto.CouponIssuanceStatusResponse;
import com.freshmarket.coupon.domain.entity.Coupon;
import com.freshmarket.coupon.domain.exception.CouponErrorCode;
import com.freshmarket.coupon.domain.exception.CouponException;
import com.freshmarket.coupon.domain.redis.CouponSeqAllocator;
import com.freshmarket.coupon.domain.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 선착순 쿠폰의 발급 현황(총 수량/발급 수/잔여)을 회원에게 보여준다.
 *
 * <p>총 수량은 {@link CouponCache}에서, 실시간 발급 수는 Redis 카운터에서 읽는다
 * ({@code docs/api/coupon.md} "발급 현황"). 둘 다 DB를 직접 안 때려서 발급 경로와 자원을
 * 안 다툰다.
 *
 * <p>이벤트가 열린 적 없거나 이미 닫혀 Redis 카운터가 없으면 DB의 {@code issued_quantity}로
 * 대신한다. 그 값은 이벤트 진행 중에는 안 맞지만, 이벤트가 없거나 종료 배치가 이미 맞춘 뒤라면
 * 정확하다.
 */
@Service
@RequiredArgsConstructor
public class CouponIssuanceStatusService {

    private final CouponCache couponCache;
    private final CouponSeqAllocator seqAllocator;
    private final CouponRepository couponRepository;

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
        int issuedQuantity = seqAllocator.currentIssuedCount(couponId)
                .orElseGet(() -> issuedQuantityFromDb(couponId));
        return new CouponIssuanceStatusResponse(totalQuantity, issuedQuantity, totalQuantity - issuedQuantity);
    }

    // Redis 카운터가 없을 때만 부른다. 이벤트가 열린 적 없거나 종료 배치가 이미 맞춘 뒤다
    private int issuedQuantityFromDb(long couponId) {
        return couponRepository.findById(couponId).map(Coupon::getIssuedQuantity).orElse(0);
    }
}
