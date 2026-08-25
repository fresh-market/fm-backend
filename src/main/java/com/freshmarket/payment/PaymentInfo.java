package com.freshmarket.payment;

import com.freshmarket.payment.domain.entity.Payment;
import java.time.LocalDateTime;

// 다른 도메인(주로 order)이 주문 상세에 결제 정보를 표시할 때 필요한 읽기 전용 값이다.
// PG 거래번호(pgTid)는 내부 정산/추적용 식별자라 외부 도메인 계약에 포함하지 않는다.
public record PaymentInfo(
        Long paymentId,
        PaymentMethod method,
        int amount,
        PaymentStatus status,
        LocalDateTime paidAt
) {

    public static PaymentInfo from(Payment payment) {
        return new PaymentInfo(payment.getId(), payment.getMethod(), payment.getAmount(),
                payment.getStatus(), payment.getPaidAt());
    }
}
