package com.freshmarket.payment.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/*
 * [2026-09-27 KST] 토스 결제창(successUrl)이 프론트에 돌려주는 값을 그대로 서버로 넘긴다.
 * orderId는 우리 주문 PK다 — 토스가 돌려주는 값은 pgOrderNo(가맹점 주문번호)뿐이라, successUrl의
 * 쿼리스트링에 orderId도 함께 실어 보내도록 프론트와 맞춘다(토스 orderId는 우리 orderId로부터
 * 결정적으로 계산되므로 서버가 다시 유도할 수도 있지만, 조회 없이 바로 쓸 수 있게 그대로 받는다).
 * amount는 토스 문서가 권장하는 대로 클라이언트가 보내온 값과 서버가 알고 있는 금액을 대조해
 * 위변조를 막는 데 쓴다(PaymentGateway.confirm 클래스 주석 참고) — 최종 신뢰 값은 항상 서버가
 * Payment에 저장해둔 amount다.
 */
public record PaymentConfirmRequest(
        @NotNull @Positive Long orderId,
        @NotBlank @Size(max = 200) String paymentKey,
        @NotNull @Positive Integer amount
) {
}
