package com.freshmarket.payment.internal.client;

import com.freshmarket.payment.PaymentRequest;

public interface PaymentGateway {

    /*
     * [2026-09-05 17:36 KST] 실패·미확정 계약 추가.
     *
     * PG에 결제 승인을 요청한다.
     *
     * 명확한 거절은 PaymentGatewayRejectedException, timeout·연결 유실처럼 결과를 알 수 없는
     * 경우는 PaymentGatewayUnknownException을 던진다. 구현체(Mock/Fake/실제 PG)는 전부 이 계약을
     * 따라야 하며, 호출하는 쪽은 후자를 FAILED로 단정하지 말고 UNKNOWN으로 다뤄야 한다.
     */
    PaymentGatewayApproval request(PaymentRequest request);

    /*
     * [2026-09-05 18:28 KST] 복구 배치가 UNKNOWN 결제의 실제 PG 측 결과를 재확인할 때 부른다.
     *
     * orderId는 request() 호출 시 PG에 함께 보낸 merchant key와 같다 — UNKNOWN 상태의 결제는
     * pgTid가 없어(성공 응답을 못 받았으니) 그걸로는 조회할 수 없고, 애초에 요청 때 보낸 식별자로
     * 물어야 한다.
     */
    PaymentGatewayInquiryResult inquire(Long orderId);
}
