package com.freshmarket.coupon.domain.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import com.freshmarket.coupon.domain.cache.CachedCoupon;
import com.freshmarket.coupon.domain.cache.CouponCache;
import com.freshmarket.coupon.domain.cache.CouponIssuanceCountCache;
import com.freshmarket.coupon.domain.dto.CouponIssuanceStatusResponse;
import com.freshmarket.coupon.domain.entity.Coupon;
import com.freshmarket.coupon.domain.exception.CouponErrorCode;
import com.freshmarket.coupon.domain.exception.CouponException;
import com.freshmarket.coupon.domain.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 선착순 쿠폰의 발급 현황(총 수량/발급 수/잔여)을 회원에게 보여준다.
 *
 * <p>총 수량은 {@link CouponCache}에서, 실시간 발급 수는 {@link CouponIssuanceCountCache}에서
 * 읽는다({@code docs/api/coupon.md} "발급 현황"). 둘 다 DB를 직접 안 때려서 발급 경로와 자원을
 * 안 다툰다.
 *
 * <p><b>Redis 값이 없을 때 DB의 issued_quantity로 메우는 것은 이벤트가 지금 살아있지 않을
 * 때만 안전하다.</b> 그 값은 이벤트를 닫을 때(syncIssuedQuantity)만 맞춰지므로, 진행 중에는
 * 시작 전 값(대개 0)에 멈춰 있다. 그래서 이벤트가 진행 중인데 Redis 를 못 읽으면 그 값을
 * 대신 보여주지 않고 실패로 답한다. 이 판단에 필요한 도메인 지식(지금 살아있는가)은
 * {@link CouponIssuanceCountCache}가 갖고 있지 않으므로 이 서비스가 대신 판단한다.
 */
@Service
@RequiredArgsConstructor
public class CouponIssuanceStatusService {

    private final CouponCache couponCache;
    private final CouponIssuanceCountCache issuanceCountCache;
    private final CouponRepository couponRepository;
    private final Clock clock;

    /**
     * @throws CouponException 그 쿠폰이 없거나(COUPON_NOT_FOUND), 선착순 대상이 아니거나
     *                          (NOT_LIMITED), 진행 중인 이벤트의 실시간 값을 못 구했으면
     *                          (ISSUANCE_STATUS_UNAVAILABLE)
     */
    public CouponIssuanceStatusResponse findStatus(long couponId) {
        CachedCoupon coupon = couponCache.find(couponId)
                .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_NOT_FOUND));
        if (!coupon.isLimited()) {
            throw new CouponException(CouponErrorCode.NOT_LIMITED);
        }
        int totalQuantity = coupon.totalQuantity();
        int issuedQuantity = issuedQuantity(couponId, coupon);
        return new CouponIssuanceStatusResponse(totalQuantity, issuedQuantity, totalQuantity - issuedQuantity);
    }

    private int issuedQuantity(long couponId, CachedCoupon coupon) {
        Optional<Integer> live = issuanceCountCache.find(couponId);
        if (live.isPresent()) {
            return live.get();
        }
        if (isLive(coupon)) {
            throw new CouponException(CouponErrorCode.ISSUANCE_STATUS_UNAVAILABLE);
        }
        return issuedQuantityFromDb(couponId);
    }

    // 이벤트가 지금 발급을 받을 수 있는 창 안인가. coupon.md 3장의 판정과 같은 식이다
    private boolean isLive(CachedCoupon coupon) {
        return coupon.active() && coupon.isIssuableAt(LocalDateTime.now(clock));
    }

    // 이벤트가 안 살아있을 때만 부른다. 오픈 전엔 0이 맞고, 종료 배치가 이미 맞춘 뒤라면 정확하다
    private int issuedQuantityFromDb(long couponId) {
        return couponRepository.findById(couponId).map(Coupon::getIssuedQuantity).orElse(0);
    }
}
