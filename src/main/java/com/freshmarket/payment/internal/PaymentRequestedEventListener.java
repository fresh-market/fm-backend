package com.freshmarket.payment.internal;

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
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/*
 * order가 발행한 결제 요청(공용 common.event)을 받아 이 도메인 안에서 PaymentApi를 부른다.
 * order는 PaymentApi를 직접 import할 수 없지만(둘 다 L2), 이 클래스는 payment.domain에 있으므로
 * 문제 없다 — 자세한 이유는 OrderPaymentRequestedEvent의 클래스 주석 참고.
 *
 * [2026-09-05 17:28 KST] @TransactionalEventListener(AFTER_COMMIT)로 변경.
 * order.OrderCreateService.createOrder()가 연 주문 생성 트랜잭션이 커밋된 뒤에야
 * 이 메서드가 실행된다(@Async는 없으므로 같은 스레드에서, 커밋 직후 이어서 실행된다).
 * 이전에는 평범한 @EventListener라 주문 트랜잭션이 열린 채로 PG 호출까지 실행됐다.
 * mock이라 즉시 끝나서 드러나지 않았을 뿐, 실제 PG나 fake PG로 timeout을 흉내내면 그만큼
 * DB 커넥션과 락을 오래 잡는 문제였다. 커밋 이후로 옮기면 이 문제가 사라지는 대신, 주문 자체는 이미
 * 확정된 뒤이므로 여기서 예외가 나도 주문 생성 자체는 롤백되지 않는다.
 * 실패 보상(주문 취소, 재고 해제)은 이 결과를 보고 별도로 처리해야 한다.
 *
 * 결제수단은 아직 API로 선택받지 않는다(쿠폰 미연동과 같은 이유로 이번 범위 밖) — 카드로 고정한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PaymentRequestedEventListener {

    private final PaymentApi paymentApi;
    private final ApplicationEventPublisher eventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
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
