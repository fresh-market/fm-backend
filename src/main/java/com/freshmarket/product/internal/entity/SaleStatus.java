package com.freshmarket.product.internal.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 상품/옵션 공통 판매 상태
@Getter
@RequiredArgsConstructor
public enum SaleStatus {
    ON_SALE("판매중"),
    SOLD_OUT("품절"),
    OFF_SALE("판매안함");

    private final String displayName;
}