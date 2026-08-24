package com.freshmarket.payment;

import java.util.Optional;

// order가 결제를 시작할 때 쓰는 payment 도메인의 공개 창구다.
public interface PaymentApi {

    PaymentResult requestPayment(PaymentRequest request);

    // 주문 상세가 결제 수단·상태를 표시할 때 쓴다. 무료 주문 등 결제 행이 없는 경우는 빈 값이다.
    Optional<PaymentInfo> findPaymentInfo(Long orderId);
}
