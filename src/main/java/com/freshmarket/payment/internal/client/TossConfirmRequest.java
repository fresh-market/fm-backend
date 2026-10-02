package com.freshmarket.payment.internal.client;

/*
 * [2026-09-27 KST] 토스 결제 승인(confirm) API 요청 바디. 토스 문서가 요구하는 필드 그대로
 * (paymentKey, orderId, amount) 세 개뿐이다 — orderId는 우리 orders.id가 아니라
 * Payment.pgOrderNo(가맹점 주문번호)다.
 */
record TossConfirmRequest(String paymentKey, String orderId, int amount) {
}
