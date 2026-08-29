package com.freshmarket.stock.domain.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 할당(StockAllocation) 상태
@Getter
@RequiredArgsConstructor
public enum AllocationStatus {
    RESERVED("예약"),
    CONFIRMED("확정"),
    RELEASED("해제");

    private final String displayName;
}
