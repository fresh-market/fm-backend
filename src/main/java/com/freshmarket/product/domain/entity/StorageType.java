package com.freshmarket.product.domain.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 상품 보관 온도
@Getter
@RequiredArgsConstructor
public enum StorageType {
    ROOM("실온"),
    COLD("냉장"),
    FROZEN("냉동");

    private final String displayName;
}