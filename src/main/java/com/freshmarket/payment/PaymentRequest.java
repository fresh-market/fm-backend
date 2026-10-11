package com.freshmarket.payment;

/*
 * order 도메인이 결제를 시작할 때 전달하는 공개 계약이다. 금액은 orders.total_amount 스냅샷이다.
 *
 * [2026-09-27 KST] memberId를 추가했다 — 결제 확정(confirm) 시점에 요청자 본인 주문인지
 * 확인해야 하는데, order/payment 둘 다 L2라 그때 가서 order에게 다시 물어볼 수 없다
 * (ArchitectureTest.도메인은_아래로만_부른다). 그래서 준비 단계에 함께 실어 Payment에
 * 스냅샷해둔다.
 */
public record PaymentRequest(
        Long orderId,
        Long memberId,
        int amount,
        PaymentMethod method
) {
}
