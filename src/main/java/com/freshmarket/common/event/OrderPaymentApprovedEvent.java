package com.freshmarket.common.event;

import java.time.LocalDateTime;

/*
 * payment가 결제 승인을 끝낸 뒤 order에게 알리는 중립 이벤트다.
 * OrderPaymentRequestedEvent와 같은 이유로 common.event에 둔다 — order/payment 둘 다 L2라
 * payment.PaymentApprovedEvent(payment 도메인 루트)를 order가 직접 구독할 수 없다.
 */
public record OrderPaymentApprovedEvent(
        Long orderId,
        Long paymentId,
        LocalDateTime paidAt
) {
}
