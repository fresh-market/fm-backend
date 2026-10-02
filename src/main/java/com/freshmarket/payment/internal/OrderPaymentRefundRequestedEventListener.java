package com.freshmarket.payment.internal;

import com.freshmarket.common.event.OrderPaymentRefundRequestedEvent;
import com.freshmarket.payment.internal.service.PaymentCancellationService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/*
 * [2026-09-27 KST] order가 이미 취소된 주문에 뒤늦게 결제 승인이 도착했을 때 발행하는
 * OrderPaymentRefundRequestedEvent를 받아 자동 환불을 트리거한다.
 *
 * PaymentRequestedEventListener와 같은 이유로 평범한 @EventListener를 쓴다(그 클래스 주석의
 * @TransactionalEventListener(AFTER_COMMIT) 시행착오 참고) — @TransactionalEventListener는
 * 리스너에서 던진 예외를 publisher에게 전파하지 않고 삼켜서 로그만 남긴다(스프링 트랜잭션 동기화
 * 콜백의 표준 동작). 이 이벤트는 publisher(OrderCreateService.onPaymentApproved)가 이미 자기
 * 트랜잭션(OrderPaymentApprovalTransactionService)을 커밋한 뒤에만 발행하므로 AFTER_COMMIT으로
 * 미룰 이유가 애초에 없고, 오히려 예외가 그대로 OrderPaymentApprovedEvent 처리 쪽까지 전파돼야
 * 그 이벤트의 outbox 재전달이 실패한 취소를 다음 주기에 다시 시도한다(PaymentCancellationService
 * 클래스 주석 참고) — AFTER_COMMIT을 썼다면 이 재시도가 조용히 사라졌을 것이다.
 *
 * PaymentRequestedEventListener와 마찬가지로 이 클래스에도 별도 테스트를 두지 않는다 — 얇은
 * 위임이라 PaymentCancellationServiceTest가 실질적인 로직을 이미 검증한다.
 */
@Component
@RequiredArgsConstructor
class OrderPaymentRefundRequestedEventListener {

    private final PaymentCancellationService paymentCancellationService;

    @EventListener
    public void onOrderPaymentRefundRequested(OrderPaymentRefundRequestedEvent event) {
        paymentCancellationService.cancelForRefund(event.paymentId(), event.reason());
    }
}
