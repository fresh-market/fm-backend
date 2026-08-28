package com.freshmarket.order.domain.entity;

import java.time.LocalDateTime;

/** 주문 생성 시점의 헤더 스냅샷. coupon 연동 전에는 관련 값이 null/0으로 고정된다. */
public record OrderPlacement(
        String requestId,
        String requestHash,
        Long memberId,
        String orderNo,
        int productAmount,
        int discountAmount,
        int shippingFee,
        int totalAmount,
        String shipRecipient,
        String shipPhone,
        String shipZipcode,
        String shipAddress,
        String shipMessage,
        LocalDateTime orderedAt
) {
}
