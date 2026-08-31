package com.freshmarket.coupon.internal.audit;

/*
 * 한 회원이 같은 쿠폰을 둘 이상 받았다.
 * uk_mc_coupon_member 가 막고 있어 나올 수 없는 값이지만, 막고 있다는 것과 실제로 없다는 것은
 * 다른 문장이다. 제약이 지워지거나 우회 경로가 생기면 여기서 드러난다.
 */
public record DuplicateIssue(long couponId, long memberId, long count) {
}
