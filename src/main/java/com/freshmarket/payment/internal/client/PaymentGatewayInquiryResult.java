package com.freshmarket.payment.internal.client;

import java.time.LocalDateTime;

/*
 * [2026-09-05 18:28 KST] PG 거래 조회(inquire) 결과. 복구 배치가 UNKNOWN 결제의 실제 PG 측 결과를
 * 재확인할 때 쓴다.
 *
 * status가 APPROVED면 pgTid/paidAt이, REJECTED면 reason이 채워진다. STILL_PROCESSING은 PG도
 * 아직 결론을 못 낸 상태라는 뜻으로, 이번에는 아무 것도 확정하지 말고 다음 배치 주기에 다시
 * 조회해야 한다.
 */
public record PaymentGatewayInquiryResult(Status status, String pgTid, LocalDateTime paidAt, String reason) {

    public enum Status {APPROVED, REJECTED, STILL_PROCESSING}

    public static PaymentGatewayInquiryResult approved(String pgTid, LocalDateTime paidAt) {
        return new PaymentGatewayInquiryResult(Status.APPROVED, pgTid, paidAt, null);
    }

    public static PaymentGatewayInquiryResult rejected(String reason) {
        return new PaymentGatewayInquiryResult(Status.REJECTED, null, null, reason);
    }

    public static PaymentGatewayInquiryResult stillProcessing() {
        return new PaymentGatewayInquiryResult(Status.STILL_PROCESSING, null, null, null);
    }
}
