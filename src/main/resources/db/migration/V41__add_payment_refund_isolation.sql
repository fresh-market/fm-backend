/*
 * [2026-09-28 KST] PG 취소(환불) 호출이 계속 실패하는 결제를 격리한다.
 * reconciliation_attempt_count/reconciliation_isolated(V37)와 같은 목적 — 영구 거절을 outbox
 * 재전달로 무한 재시도하는 대신 상한을 넘기면 멈추고 운영자가 확인하게 한다
 * (PaymentCancellationService/Payment.recordUnresolvedRefundAttempt 참고).
 */
ALTER TABLE payment
    ADD COLUMN refund_attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN refund_isolated BOOLEAN NOT NULL DEFAULT FALSE;
