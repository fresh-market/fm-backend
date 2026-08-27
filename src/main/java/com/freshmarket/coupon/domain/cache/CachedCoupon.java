package com.freshmarket.coupon.domain.cache;

import java.time.LocalDateTime;

import com.freshmarket.coupon.domain.entity.Coupon;
import com.freshmarket.coupon.domain.entity.CouponScope;

/**
 * 발급 경로가 쿠폰에서 보는 값만 담은 불변 스냅샷이다.
 *
 * <p>엔티티를 그대로 캐시하지 않는 이유가 둘이다. 하나는 JPA 엔티티가 가변이라 여러 요청
 * 스레드가 나눠 갖기에 안전하지 않다는 것이고, 다른 하나는 이 record 가 담은 값들만
 * 발급 창 안에서 얼어붙는다는 것이다({@code docs/coupon/coupon.md} 3장).
 *
 * <p>판정 메서드를 엔티티가 아니라 여기에 둔다. 양쪽에 같은 식을 두면 한쪽만 고쳤을 때
 * <b>캐시가 켜졌을 때와 꺼졌을 때 답이 달라진다.</b> 식이 한 군데만 있어야 그 일이 안 생긴다.
 */
public record CachedCoupon(long couponId,
                           CouponScope scope,
                           Integer totalQuantity,
                           LocalDateTime issueStartAt,
                           LocalDateTime issueEndAt,
                           Long targetGradeId,
                           boolean active) {

    public static CachedCoupon from(Coupon coupon) {
        return new CachedCoupon(
                coupon.getId(),
                coupon.getScope(),
                coupon.getTotalQuantity(),
                coupon.getIssueStartAt(),
                coupon.getIssueEndAt(),
                coupon.getTargetGradeId(),
                coupon.isActive());
    }

    /** 수량이 정해진 쿠폰만 선착순 대상이다. */
    public boolean isLimited() {
        return totalQuantity != null;
    }

    /** 발급 창 안인가. 시작이 없으면 열려 있고, 끝이 없으면 소진까지 연다. */
    public boolean isIssuableAt(LocalDateTime now) {
        if (issueStartAt != null && now.isBefore(issueStartAt)) {
            return false;
        }
        return issueEndAt == null || !now.isAfter(issueEndAt);
    }

    /** 대상 등급이 없으면 누구나 받는다. */
    public boolean isTargetGrade(Long memberGradeId) {
        return targetGradeId == null || targetGradeId.equals(memberGradeId);
    }
}
