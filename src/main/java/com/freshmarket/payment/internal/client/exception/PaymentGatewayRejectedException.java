package com.freshmarket.payment.internal.client.exception;

// [2026-09-05 17:36 KST] PG가 결제를 명확히 거절했을 때 던진다. 재시도해도 같은 결과가 나오는
// 확정된 실패라, 이 예외를 받은 쪽은 Payment를 FAILED로 확정해도 된다.
public class PaymentGatewayRejectedException extends RuntimeException {

    public PaymentGatewayRejectedException(String reason) {
        super(reason);
    }
}
