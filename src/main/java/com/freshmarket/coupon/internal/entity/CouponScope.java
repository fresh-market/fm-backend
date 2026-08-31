package com.freshmarket.coupon.internal.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 쿠폰 적용 범위. member_coupon 이 복합 외래 키로 이 값을 coupon 과 맞춘다
@Getter
@RequiredArgsConstructor
public enum CouponScope {
    ORDER("장바구니 쿠폰"),
    ITEM("상품 쿠폰");

    private final String displayName;
}
