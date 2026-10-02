package com.freshmarket.payment.internal.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/*
 * [2026-09-27 KST] 토스 결제 승인(confirm)/조회(inquire) API가 공통으로 돌려주는 Payment 객체 중
 * 우리가 실제로 쓰는 필드만 옮겨 담는다. 토스 응답에는 card/virtualAccount/receipt 등 결제수단별
 * 상세 필드가 훨씬 많지만 이 프로젝트는 method(수단 이름)만 필요하다.
 *
 * @JsonIgnoreProperties(ignoreUnknown = true)를 명시로 남긴다 — Spring Boot 기본 ObjectMapper가
 * FAIL_ON_UNKNOWN_PROPERTIES를 이미 꺼두지만, 이 DTO만 보고도 "토스가 필드를 더 보내도 안전하다"는
 * 의도가 드러나야 나중에 필드를 추가할 때 실수로 strict 매퍼를 쓰는 일을 막는다.
 *
 * status는 confirm 응답과 inquire 응답에서 같은 enum 값 집합을 쓴다(DONE/CANCELED/PARTIAL_CANCELED/
 * WAITING_FOR_DEPOSIT/IN_PROGRESS/ABORTED/EXPIRED 등, 토스 문서 "결제 상태" 참고). 여기서는
 * TossPaymentGateway가 그 문자열을 직접 비교하므로 별도 enum으로 옮기지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record TossPaymentResponse(
        String paymentKey,
        String orderId,
        String status,
        String approvedAt,
        String method,
        Failure failure
) {

    // ABORTED/EXPIRED처럼 승인에 실패한 결제 조회 응답에만 채워진다.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Failure(String code, String message) {
    }
}
