package com.freshmarket.stock.internal.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 재고 변동 유형
@Getter
@RequiredArgsConstructor
public enum MovementType {
    INBOUND("신규 입고"),
    RESTOCK("반품 재입고"),
    RESERVE("예약"),
    CONFIRM("차감 확정"),
    RELEASE("예약 해제"),
    DISPOSE("폐기"),
    EXPIRE("만료 전환"),
    ADJUST("수동 조정");

    private final String displayName;
}
