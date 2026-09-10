package com.freshmarket.common.event;

/*
 * payment가 결제를 최종 실패(FAILED)로 확정한 뒤 order에게 알리는 중립 이벤트다.
 * OrderPaymentApprovedEvent와 같은 이유로 common.event에 둔다 — order/payment 둘 다 L2라
 * payment 도메인 내부 타입을 order가 직접 구독할 수 없다.
 *
 * [2026-09-05 19:13 KST] UNKNOWN 상태에서는 이 이벤트가 발행되지 않는다 — 아직 PG 결과가
 * 확정되지 않아 주문을 섣불리 취소하면 안 되기 때문이다(복구 배치가 나중에 PAID로 확정할 수도
 * 있다). PaymentService.failPayment()가 실제로 FAILED로 전이할 때만(PENDING에서의 최초
 * 거절이든, 복구 배치가 뒤늦게 확정하는 것이든) 발행한다.
 */
public record OrderPaymentFailedEvent(
        Long orderId,
        Long paymentId,
        String reason
) {
}
