package com.freshmarket.stock.domain.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 폐기 사유
@Getter
@RequiredArgsConstructor
public enum DisposalReason {
    EXPIRED("소비기한 경과"),
    DAMAGED("손상"),
    RETURNED("재입고하지 않은 회수품");

    private final String displayName;
}
