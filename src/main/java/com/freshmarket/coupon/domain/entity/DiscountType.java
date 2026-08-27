package com.freshmarket.coupon.domain.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 할인 유형
@Getter
@RequiredArgsConstructor
public enum DiscountType {
    AMOUNT("정액"),
    RATE("정률");

    private final String displayName;
}
