package com.freshmarket.payment;

// order 도메인이 결제를 시작할 때 전달하는 공개 계약이다. 금액은 orders.total_amount 스냅샷이다.
public record PaymentRequest(
        Long orderId,
        int amount,
        PaymentMethod method
) {
}
