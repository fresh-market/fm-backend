package com.freshmarket.payment.internal.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// [2026-09-27 KST] 토스 API가 4xx/5xx일 때 돌려주는 에러 바디 형식: {"code": "...", "message": "..."}.
// TossPaymentGateway가 code로 명확한 거절/원인불명을 가른다(TossPaymentGateway.DEFINITE_REJECT_CODES
// 참고).
@JsonIgnoreProperties(ignoreUnknown = true)
record TossErrorResponse(String code, String message) {
}
