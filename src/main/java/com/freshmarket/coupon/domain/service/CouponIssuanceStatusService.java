package com.freshmarket.coupon.domain.service;

import com.freshmarket.coupon.domain.cache.CachedCoupon;
import com.freshmarket.coupon.domain.cache.CouponCache;
import com.freshmarket.coupon.domain.dto.CouponIssuanceStatusResponse;
import com.freshmarket.coupon.domain.entity.Coupon;
import com.freshmarket.coupon.domain.exception.CouponErrorCode;
import com.freshmarket.coupon.domain.exception.CouponException;
import com.freshmarket.coupon.domain.exception.DataAccessFailures;
import com.freshmarket.coupon.domain.redis.CouponSeqAllocator;
import com.freshmarket.coupon.domain.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
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
        int issuedQuantity = issuedQuantity(couponId);
        return new CouponIssuanceStatusResponse(totalQuantity, issuedQuantity, totalQuantity - issuedQuantity);
    }

    /*
     * Redis 가 "카운터 없음"으로 정상 응답하면 DB 로 대체한다(이벤트 미오픈/종료 후).
     * Redis 자체가 일시적으로 응답하지 못해도 같은 값으로 대체한다 — 발급 경로는 멀쩡한데
     * 현황 조회만 실패로 답하면 안 된다. 다만 일시적이지 않은 실패(코드 버그 등)까지 덮으면
     * 그 버그가 조용히 묻히므로 DataAccessFailures.isTransient 로 가른다.
     */
    private int issuedQuantity(long couponId) {
        try {
            return seqAllocator.currentIssuedCount(couponId).orElseGet(() -> issuedQuantityFromDb(couponId));
        } catch (DataAccessException e) {
            if (DataAccessFailures.isTransient(e)) {
                return issuedQuantityFromDb(couponId);
            }
            throw e;
        }
    }

    // Redis 카운터가 없거나 못 읽을 때 부른다. 이벤트가 열린 적 없거나 종료 배치가 이미 맞춘 뒤다
    private int issuedQuantityFromDb(long couponId) {
        return couponRepository.findById(couponId).map(Coupon::getIssuedQuantity).orElse(0);
    }
}
