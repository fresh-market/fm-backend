package com.freshmarket.payment;

import java.time.LocalDateTime;

// 결제가 커밋된 뒤 order 도메인이 주문 상태와 후속 처리를 갱신할 때 받는 사실 이벤트다.
public record PaymentApprovedEvent(
        Long paymentId,
        Long orderId,
        LocalDateTime paidAt
) {
}
