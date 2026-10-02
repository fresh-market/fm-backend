package com.freshmarket.payment.internal.dto;

import com.freshmarket.payment.PaymentResult;
import com.freshmarket.payment.PaymentStatus;

// 확정(confirm) 결과를 프론트에 돌려준다. PENDING이 아니면(PAID/FAILED/UNKNOWN) 프론트가 그대로 안내 화면을 그린다.
public record PaymentConfirmResponse(
        Long orderId,
        PaymentStatus status
) {
    public static PaymentConfirmResponse from(PaymentResult result) {
        return new PaymentConfirmResponse(result.orderId(), result.status());
    }
}
