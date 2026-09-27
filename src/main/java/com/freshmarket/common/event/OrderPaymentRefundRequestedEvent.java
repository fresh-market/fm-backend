package com.freshmarket.common.event;

/*
 * [2026-09-27 KST] order가 이미 CANCELED로 확정한 주문에 뒤늦게 결제 승인이 도착했을 때 발행하는
 * 중립 이벤트다(OrderCreateService.onPaymentApproved()의 "TODO: 자동 환불 로직 추가" 항목을
 * 해소한다). payment 도메인이 이 이벤트를 구독해 실제로 PaymentGateway.cancel()을 불러 환불한다 —
 * order/payment 둘 다 L2라 서로 직접 못 부르는 제약은 OrderPaymentApprovedEvent/
 * OrderPaymentFailedEvent와 같다(그 클래스들 주석 참고).
 *
 * 이 이벤트는 OrderPaymentApprovalTransactionService.applyApproval()의 DB 트랜잭션이 커밋되어
 * 반환된 "뒤"에만 발행된다 — 이 이벤트를 처리하는 쪽이 결국 PG 호출(PaymentGateway.cancel)로
 * 이어지므로, 그 호출이 열린 DB 트랜잭션 안에서 나가면 안 된다는 원칙(주문 인수인계 문서 5장
 * "PG 호출과 DB 트랜잭션 경계")을 지키기 위해서다.
 */
public record OrderPaymentRefundRequestedEvent(
        Long orderId,
        Long paymentId,
        String reason
) {
}
