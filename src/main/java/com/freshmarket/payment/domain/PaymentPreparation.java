package com.freshmarket.payment.domain;

import com.freshmarket.payment.domain.entity.Payment;

// gateway 호출 권한은 새 PENDING 결제를 만든 최초 요청 하나에만 준다.
public record PaymentPreparation(
        Payment payment,
        boolean newlyPrepared
) {
}
