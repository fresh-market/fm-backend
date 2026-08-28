package com.freshmarket.payment.domain;

import com.freshmarket.common.event.OrderPaymentApprovedEvent;
import com.freshmarket.common.event.OrderPaymentRequestedEvent;
import com.freshmarket.payment.PaymentApi;
import com.freshmarket.payment.PaymentMethod;
import com.freshmarket.payment.PaymentRequest;
import com.freshmarket.payment.PaymentResult;
import com.freshmarket.payment.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/*
 * order가 발행한 결제 요청(공용 common.event)을 받아 이 도메인 안에서 PaymentApi를 부른다.
 * order는 PaymentApi를 직접 import할 수 없지만(둘 다 L2), 이 클래스는 payment.domain에 있으므로
 * 문제 없다 — 자세한 이유는 OrderPaymentRequestedEvent의 클래스 주석 참고.
 *
 * @EventListener는 기본이 동기다(@Async 없음) — order.OrderCreateService.createOrder()가 연
 * 트랜잭션 안에서 이 메서드까지 같은 스레드로 이어져 실행된다(cart의
 * MemberRegisteredEvent -> CartService.createCartForNewMember 패턴과 동일). 여기서 예외가 나면
 * 그대로 주문 생성 트랜잭션 전체가 롤백된다 — 지금은 결제가 항상 mock으로 성공하므로 실제로 이
 * 경로를 탈 일은 없지만, 실패 시 별도 보상 로직(재고 해제 등)이 아직 없다는 뜻이기도 하다.
 *
 * 결제수단은 아직 API로 선택받지 않는다(쿠폰 미연동과 같은 이유로 이번 범위 밖) — 카드로 고정한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PaymentRequestedEventListener {

    private final PaymentApi paymentApi;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    public void onPaymentRequested(OrderPaymentRequestedEvent event) {
        PaymentResult result = paymentApi.requestPayment(
                new PaymentRequest(event.orderId(), event.amount(), PaymentMethod.CARD));

        if (result.status() != PaymentStatus.PAID) {
            // 실패/미확정 결제를 order에 되돌리는 이벤트나 주문 취소 로직은 아직 없다(mock 결제만
            // 있는 지금 단계 범위 밖). 지금은 로그만 남기고 주문은 PAYMENT_PENDING으로 남는다.
            log.warn("payment not approved as PAID. orderId={}, status={}", event.orderId(), result.status());
            return;
        }

        eventPublisher.publishEvent(
                new OrderPaymentApprovedEvent(result.orderId(), result.paymentId(), result.paidAt()));
    }
}
