package com.freshmarket.order.domain.entity;

/** 주문 생성 시점의 항목 스냅샷. sourceCartItemId는 응답에 노출하지 않는 내부 추적 값이다. */
public record OrderItemPlacement(
        Long orderId,
        Long sourceCartItemId,
        Long productOptionId,
        String nameSnapshot,
        String optionNameSnapshot,
        int unitPrice,
        int qty,
        Long memberId,
        int discountAmount
) {
}
