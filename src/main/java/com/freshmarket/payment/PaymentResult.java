package com.freshmarket.payment;

import com.freshmarket.payment.domain.entity.Payment;
import java.time.LocalDateTime;

public record PaymentResult(
        Long paymentId,
        Long orderId,
        PaymentMethod method,
        int amount,
        PaymentStatus status,
        String pgTid,
        LocalDateTime paidAt
) {

    public static PaymentResult from(Payment payment) {
        return new PaymentResult(payment.getId(), payment.getOrderId(), payment.getMethod(),
                payment.getAmount(), payment.getStatus(), payment.getPgTid(), payment.getPaidAt());
    }
}
