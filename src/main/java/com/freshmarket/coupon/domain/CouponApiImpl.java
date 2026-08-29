package com.freshmarket.coupon.domain;

import com.freshmarket.coupon.CouponApi;
import com.freshmarket.coupon.domain.service.MemberCouponStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/*
 * coupon 도메인이 밖에 무엇을 제공하는지 한 곳에서 보이게 domain 바로 아래에 둔다.
 *
 * 이 클래스는 트랜잭션 경계를 갖지 않는다. 경계를 서비스 한 곳에 모으는 규칙이고 ArchUnit 이 그것을 지킨다.
 * 상태 전이와 이력 기록이 한 트랜잭션이어야 하는 것은 MemberCouponStatusService 가 지킨다.
 */
@Component
@RequiredArgsConstructor
class CouponApiImpl implements CouponApi {

    private final MemberCouponStatusService memberCouponStatusService;

    @Override
    public void useCoupon(Long memberCouponId, Long memberId) {
        memberCouponStatusService.use(memberCouponId, memberId);
    }

    @Override
    public void cancelCouponUse(Long memberCouponId, Long memberId) {
        memberCouponStatusService.cancelUse(memberCouponId, memberId);
    }
}
