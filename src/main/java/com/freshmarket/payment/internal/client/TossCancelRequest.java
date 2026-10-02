package com.freshmarket.payment.internal.client;

/*
 * [2026-09-27 KST] 토스 결제 취소(cancel) API 요청 바디다. cancelAmount를 아예 보내지 않으면
 * 토스가 잔액 전체를 취소 처리한다(토스 문서) — 이 프로젝트는 전액 환불만 지원하므로
 * (PaymentGateway.cancel() 주석 참고) cancelReason 하나만 보낸다.
 */
record TossCancelRequest(String cancelReason) {
}
