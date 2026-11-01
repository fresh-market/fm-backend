package com.freshmarket.payment;

import java.util.Optional;

/*
 * order가 결제를 시작할 때 쓰는 payment 도메인의 공개 창구다.
 *
 * [2026-09-27 KST] requestPayment(PG 승인까지 동기로 진행)에서 preparePayment(PENDING 행만
 * 만들고 끝남)로 이름을 바꿨다 — 토스 연동으로 실제 PG 승인은 프론트가 결제창에서 인증을 마친 뒤
 * 별도 확정(confirm) API에서 이뤄지기 때문이다. confirm은 이 인터페이스에 넣지 않았다 — order가
 * 아니라 결제창에서 돌아온 프론트가 직접 부르는 엔드포인트라 order 도메인이 호출할 일이 없고,
 * payment 내부의 PaymentConfirmController가 같은 도메인의 PaymentConfirmationService를 직접
 * 부른다(domain-package-boundary-guideline.md의 "같은 도메인 컨트롤러는 내부 서비스를 직접 쓴다"
 * 규칙 참고).
 */
public interface PaymentApi {

    PaymentResult preparePayment(PaymentRequest request);

    // 주문 상세가 결제 수단·상태를 표시할 때 쓴다. 무료 주문 등 결제 행이 없는 경우는 빈 값이다.
    Optional<PaymentInfo> findPaymentInfo(Long orderId);
}
