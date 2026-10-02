package com.freshmarket.payment.internal.client;

import com.freshmarket.payment.PaymentMethod;
import java.time.LocalDateTime;

/*
 * [2026-09-27 KST] method를 추가했다 — 토스 결제창은 사용자가 결제수단을 직접 고르므로, PG가 실제로
 * 승인한 수단은 준비 단계에 고정해둔 값(현재는 CARD)과 다를 수 있다. confirm 응답에 담겨 오는 실제
 * 값을 여기 실어 Payment.approve()에 그대로 전달한다.
 */
public record PaymentGatewayApproval(
        String pgTid,
        LocalDateTime paidAt,
        PaymentMethod method
) {
}
