package com.freshmarket.stock.internal.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 로트 상태
@Getter
@RequiredArgsConstructor
public enum LotStatus {
    AVAILABLE("판매 가능"),
    SOLD_OUT("소진"),
    DISPOSED("폐기"),
    EXPIRED("소비기한 경과");

    private final String displayName;
}
