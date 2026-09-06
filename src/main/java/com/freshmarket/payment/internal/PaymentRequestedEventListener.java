package com.freshmarket.payment.internal;

import com.freshmarket.common.event.OrderPaymentRequestedEvent;
import com.freshmarket.payment.PaymentApi;
import com.freshmarket.payment.PaymentMethod;
import com.freshmarket.payment.PaymentRequest;
import com.freshmarket.payment.PaymentResult;
import com.freshmarket.payment.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/*
 * order가 발행한 결제 요청(공용 common.event)을 받아 이 도메인 안에서 PaymentApi를 부른다.
 * order는 PaymentApi를 직접 import할 수 없지만(둘 다 L2), 이 클래스는 payment.domain에 있으므로
 * 문제 없다 — 자세한 이유는 OrderPaymentRequestedEvent의 클래스 주석 참고.
 *
 * [2026-09-05 17:28 KST] @TransactionalEventListener(AFTER_COMMIT)로 변경. 주문 생성 트랜잭션이
 * 커밋된 뒤에야 이 메서드가 실행된다(@Async는 없으므로 같은 스레드에서, 커밋 직후 이어서 실행된다).
 * 이전에는 평범한 @EventListener라 주문 트랜잭션이 열린 채로 PG 호출까지 실행됐다 — mock이라 즉시
 * 끝나서 드러나지 않았을 뿐, 실제 PG나 fake PG로 timeout을 흉내내면 그만큼 DB 커넥션과 락을 오래
 * 잡는 문제였다.
 *
 * [2026-09-05 19:13 KST] order에게 승인/실패를 알리는 이벤트(OrderPaymentApprovedEvent/
 * OrderPaymentFailedEvent) 발행은 더 이상 여기서 하지 않는다 — PaymentService.approvePayment()/
 * failPayment()가 실제로 상태를 전이시키는 그 순간에 직접 발행한다. 그래야 지금 이 동기 흐름에서
 * 확정되는 경우든, 복구 배치(PaymentReconciliationService)가 나중에 UNKNOWN을 뒤늦게 확정하는
 * 경우든 같은 한 곳에서 발행돼 order가 놓치지 않는다(전에는 여기서만 발행해서 복구 배치가 확정하는
 * 경로는 이벤트가 아예 안 나갔다). 여기서는 결과를 로그로만 남긴다.
 *
 * 결제수단은 아직 API로 선택받지 않는다(쿠폰 미연동과 같은 이유로 이번 범위 밖) — 카드로 고정한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PaymentRequestedEventListener {

    private final PaymentApi paymentApi;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentRequested(OrderPaymentRequestedEvent event) {
        PaymentResult result = paymentApi.requestPayment(
                new PaymentRequest(event.orderId(), event.amount(), PaymentMethod.CARD));

        if (result.status() == PaymentStatus.UNKNOWN) {
            log.info("payment result unknown, awaiting reconciliation. orderId={}", event.orderId());
        } else if (result.status() != PaymentStatus.PAID) {
            log.info("payment not approved. orderId={}, status={}", event.orderId(), result.status());
        }
    }
}
