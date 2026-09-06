package com.freshmarket.payment.internal;

import com.freshmarket.common.event.OrderPaymentRequestedEvent;
import com.freshmarket.payment.PaymentApi;
import com.freshmarket.payment.PaymentMethod;
import com.freshmarket.payment.PaymentRequest;
import com.freshmarket.payment.PaymentResult;
import com.freshmarket.payment.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/*
 * order가 발행한 결제 요청(공용 common.event)을 받아 이 도메인 안에서 PaymentApi를 부른다.
 * order는 PaymentApi를 직접 import할 수 없지만(둘 다 L2), 이 클래스는 payment.domain에 있으므로
 * 문제 없다 — 자세한 이유는 OrderPaymentRequestedEvent의 클래스 주석 참고.
 *
 * [2026-09-05 17:28 KST] 한때 @TransactionalEventListener(AFTER_COMMIT)를 썼었다. 주문 생성
 * 트랜잭션이 열린 채로 PG 호출까지 실행되는 문제(mock이라 즉시 끝나서 드러나지 않았을 뿐, 실제
 * PG나 fake PG로 timeout을 흉내내면 DB 커넥션과 락을 오래 잡는 문제였다) 때문이었다.
 *
 * [2026-09-06 KST] 평범한 @EventListener로 되돌렸다. OrderCreateService.createOrder()가
 * OrderPaymentRequestedEvent를 발행하는 지점(b단계, 그 클래스 주석 참고)은 orderPendingCreationService
 * .createPendingOrder()의 트랜잭션이 이미 커밋되고 리턴한 "뒤"라, publishEvent()가 호출되는 시점엔
 * 활성 트랜잭션이 전혀 없다. @TransactionalEventListener는 발행 시점에 활성 트랜잭션(정확히는
 * TransactionSynchronizationManager 동기화)이 있어야 그 커밋/롤백 콜백에 자신을 등록할 수 있는데,
 * 여기선 등록할 대상 자체가 없어서 리스너가 예외 없이 그냥 조용히 실행되지 않았다(fallbackExecution
 * 기본값이 false라 로그도 DEBUG 레벨 한 줄뿐이라 알아채기 어렵다) — 실제로 결제가 단 한 번도
 * 요청되지 않는 버그였고, OrderPaymentFlowIntegrationTest를 만들고 나서야 드러났다(Mockito 단위
 * 테스트는 스프링의 실제 트랜잭션 동기화를 흉내내지 않아 이 문제를 잡을 수 없었다).
 *
 * 지금 구조에서는 @EventListener든 @TransactionalEventListener든 실제로 실행되는 시점은 동일하다
 * — publishEvent() 호출 자체가 이미 주문 생성 트랜잭션의 커밋 "이후"에 코드 순서상 일어나기
 * 때문이다. 즉 "커밋 뒤에 실행"이라는 목표는 이벤트 발행 위치 자체로 이미 달성되어 있어서, 여기서는
 * 스프링의 트랜잭션 동기화 콜백이 필요 없다(오히려 위 이유로 아예 실행을 막는다).
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

    @EventListener
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
