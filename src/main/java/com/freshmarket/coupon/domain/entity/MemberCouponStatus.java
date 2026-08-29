package com.freshmarket.coupon.domain.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 발급분 상태
@Getter
@RequiredArgsConstructor
public enum MemberCouponStatus {
    ISSUED("발급"),
    USED("사용"),
    EXPIRED("만료"),
    CANCELED("주문 취소로 사용 철회");

    private final String displayName;
}
