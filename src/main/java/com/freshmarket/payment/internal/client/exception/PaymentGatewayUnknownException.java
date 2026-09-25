package com.freshmarket.payment.internal.client.exception;

// [2026-09-05 17:36 KST] PG 응답이 timeout이나 연결 유실 등으로 결과를 알 수 없을 때 던진다.
// 거절과 달리 실제로는 승인됐을 수도 있으므로, 이 예외를 받은 쪽은 Payment를 FAILED로 단정하지
// 말고 UNKNOWN으로 기록한 뒤 PG 거래 조회(reconciliation)로 재확인해야 한다.
public class PaymentGatewayUnknownException extends RuntimeException {

    public PaymentGatewayUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
