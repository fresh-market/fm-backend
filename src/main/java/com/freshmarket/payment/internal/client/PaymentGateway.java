package com.freshmarket.payment.internal.client;

public interface PaymentGateway {

    /*
     * [2026-09-05 17:36 KST] 실패·미확정 계약 추가.
     *
     * [2026-09-27 KST] request(PaymentRequest)에서 confirm(paymentKey, pgOrderNo, amount)로
     * 바꿨다 — 토스는 프론트가 결제창에서 결제수단 선택과 인증까지 마친 뒤 그 결과로 받은
     * paymentKey를 서버가 최종 승인(confirm)하는 구조라, 서버가 PaymentRequest 전체(주문 생성
     * 시점 정보)를 다시 PG에 보내는 게 아니라 paymentKey·가맹점 주문번호(pgOrderNo)·금액만
     * 보내면 된다. 금액은 여기서 다시 한 번 명시적으로 넘겨받아 호출하는 쪽(PaymentConfirmationService)이
     * Payment에 저장된 금액과 같은 값을 넘기도록 강제한다 — 토스 confirm API 자체도 금액 불일치를
     * 거절 사유로 쓴다.
     *
     * 명확한 거절은 PaymentGatewayRejectedException, timeout·연결 유실처럼 결과를 알 수 없는
     * 경우는 PaymentGatewayUnknownException을 던진다. 구현체(Mock/Fake/실제 PG)는 전부 이 계약을
     * 따라야 하며, 호출하는 쪽은 후자를 FAILED로 단정하지 말고 UNKNOWN으로 다뤄야 한다.
     */
    PaymentGatewayApproval confirm(String paymentKey, String pgOrderNo, int amount);

    /*
     * [2026-09-05 18:28 KST] 복구 배치가 UNKNOWN 결제의 실제 PG 측 결과를 재확인할 때 부른다.
     *
     * [2026-09-27 KST] Long orderId에서 String pgOrderNo로 바꿨다 — 토스 조회 API는 가맹점
     * 주문번호(Payment.pgOrderNo)로 조회한다. confirm() 시도 시 PG에 보낸 값과 같다 — UNKNOWN
     * 상태의 결제는 pgTid가 없어(성공 응답을 못 받았으니) 그걸로는 조회할 수 없고, 애초에 confirm
     * 시도 때 보낸 식별자로 물어야 한다.
     */
    PaymentGatewayInquiryResult inquire(String pgOrderNo);

    /*
     * [2026-09-27 KST] order 쪽에서 이미 취소된 주문에 뒤늦게 결제가 승인된 경우의 자동 환불에
     * 쓴다(common.event.OrderPaymentRefundRequestedEvent 참고). 전액 취소만 지원한다 — 부분
     * 환불은 이 프로젝트가 아직 필요로 하지 않는다(YAGNI, 필요해지면 별도 금액 파라미터를 추가한
     * 메서드를 새로 만든다).
     *
     * paymentKey는 이 결제를 승인할 때 PG가 돌려준 거래 식별자(Payment.pgTid)다 — confirm()에
     * 보낸 것과 같은 값이며, 토스 취소 API는 이 값으로 대상 결제를 특정한다.
     *
     * 구현체는 이미 취소된 결제에 대한 중복 호출을 멱등하게 흡수해야 한다 — 이벤트 재전달로 같은
     * 결제에 대해 여러 번 불릴 수 있다(호출하는 쪽도 Payment.cancel()의 멱등 가드로 이중 방어한다).
     */
    void cancel(String paymentKey, String reason);
}
