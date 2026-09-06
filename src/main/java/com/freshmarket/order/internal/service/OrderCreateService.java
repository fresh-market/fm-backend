package com.freshmarket.order.internal.service;

import com.freshmarket.common.event.OrderPaymentApprovedEvent;
import com.freshmarket.common.event.OrderPaymentFailedEvent;
import com.freshmarket.common.event.OrderPaymentRequestedEvent;
import com.freshmarket.order.internal.PendingOrderResult;
import com.freshmarket.order.internal.dto.OrderCreateRequest;
import com.freshmarket.order.internal.dto.OrderCreateResponse;
import com.freshmarket.order.internal.entity.Order;
import com.freshmarket.order.internal.entity.OrderItem;
import com.freshmarket.order.internal.entity.OrderStatus;
import com.freshmarket.order.internal.repository.OrderItemRepository;
import com.freshmarket.order.internal.repository.OrderRepository;
import com.freshmarket.stock.StockApi;
import com.freshmarket.stock.StockOrderItemsRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/*
 * 장바구니 -> 주문 생성(POST /v1/orders)의 공개 진입점이다. 클래스/메서드 어디에도 @Transactional을
 * 달지 않는다 — 아래 세 단계가 각자 자기 트랜잭션을 갖고, 결제 요청은 열린 DB 트랜잭션이 전혀 없는
 * 상태에서 나간다. 이건 팀이 미리 정해둔 경계다(주문 인수인계 문서 5장 "PG 호출과 DB 트랜잭션 경계"):
 *
 *   a. orderPendingCreationService.createPendingOrder(...) — 짧은 트랜잭션. 주문/주문상품 저장,
 *      재고 예약, 장바구니 정리까지 끝내고 커밋한 뒤 돌아온다.
 *   b. (여기, 트랜잭션 밖) OrderPaymentRequestedEvent 발행 — payment.domain의 리스너가 이
 *      이벤트를 받아 PaymentApi.requestPayment를 부른다(자세한 이유는 그 이벤트 클래스 주석:
 *      order/payment 둘 다 L2라 서로 직접 못 부른다). 지금은 MockPaymentGateway라 이 호출이
 *      순식간에 끝나지만, 나중에 실제 PG WebClient로 바뀌어 네트워크 지연이 생겨도 이 시점엔 DB
 *      락을 하나도 쥐고 있지 않다 — PG 호출을 열린 트랜잭션 밖으로 빼는 게 이 구조의 핵심이다.
 *      "mock 성공만 리턴하는 지점"을 한 곳으로 좁히고 싶다면 손댈 곳은 이 이벤트 발행부가 아니라
 *      payment.domain.client.MockPaymentGateway 하나다 — PaymentGateway 인터페이스의 구현체를
 *      실제 PG 클라이언트로 교체하는 것만으로 끝난다(PaymentApiImpl/PaymentService는 안 바뀐다).
 *   c. onPaymentApproved/onPaymentFailed(아래) — 결제 결과가 확정되면 이벤트 체인 끝에서 새로
 *      짧은 트랜잭션을 연다.
 *
 * a단계를 이 클래스 안의 @Transactional 메서드로 두지 않고 별도 빈(OrderPendingCreationService)
 * 으로 뺀 이유: 같은 빈 안에서 this.메서드()로 자기 자신을 호출하면 스프링 프록시를 안 거쳐
 * @Transactional이 조용히 무시된다(자기호출 함정) — TransactionTemplate으로 직접 경계를 긋는
 * 방법도 있지만, 이 프로젝트는 지금까지 전부 선언적 @Transactional만 써왔어서(TransactionTemplate
 * 쓰는 곳이 없다) 스타일을 맞추는 쪽을 택했다.
 *
 * [2026-09-05 19:13 KST] onPaymentApproved/onPaymentFailed는 원래 평범한 @EventListener였는데
 * @TransactionalEventListener(AFTER_COMMIT)로 바꿨다. PaymentService.approvePayment()/
 * failPayment()가 이제 "자기 트랜잭션 안에서" 이벤트를 발행하기 때문이다 — 발행 시점엔 payment
 * 쪽 트랜잭션이 아직 커밋 전이라, 평범한 @EventListener라면 이 메서드가 payment의 커밋 여부와
 * 무관하게 같은 호출 스택 안에서 즉시 실행돼 버린다(그 상태에서 이 메서드가 실패하면 아직 커밋도
 * 안 한 payment 트랜잭션까지 함께 말려들 위험도 있다). AFTER_COMMIT으로 두면 payment 쪽
 * 트랜잭션이 실제로 커밋된 뒤에만 실행되고, 여기서 예외가 나도 이미 커밋된 payment 상태는
 * 되돌리지 않는다 — order 쪽 실패가 이미 확정된 결제를 롤백시키면 안 되기 때문이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCreateService {

    private final OrderPendingCreationService orderPendingCreationService;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockApi stockApi;
    private final ApplicationEventPublisher eventPublisher;

    public OrderCreateResponse createOrder(Long memberId, OrderCreateRequest request) {
        PendingOrderResult pending = orderPendingCreationService.createPendingOrder(memberId, request);
        if (!pending.newlyCreated()) {
            // requestId 재시도로 기존 주문을 그대로 돌려주는 경우 — 결제를 다시 요청하지 않는다.
            return pending.response();
        }

        eventPublisher.publishEvent(new OrderPaymentRequestedEvent(
                pending.response().orderId(), pending.response().totalAmount()));

        return pending.response();
    }

    /*
     * 결제 승인 뒤 payment 도메인이 발행한 이벤트를 받아 주문을 PAID로 바꾸고 재고를 확정한다.
     * createOrder()의 a단계와는 별개의 새 트랜잭션이다(위 클래스 주석의 c단계).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional
    public void onPaymentApproved(OrderPaymentApprovedEvent event) {
        /*
         * [2026-09-06 KST] 이 orderId는 createOrder()에서 주문 저장 커밋이 끝난 뒤에야(오토인크리먼트로
         * 값을 이미 받은 뒤에야) OrderPaymentRequestedEvent에 실려 나갔다가 결제 승인 이벤트에 그대로
         * 돌아온 것이라, 정상 흐름에서는 여기서 주문을 못 찾는 경로가 없다 — 즉 이 예외가 실제로
         * 던져진다면 재시도로 풀릴 일시적 문제가 아니라 버그 신호다(같은 id로 다시 조회해도 똑같이
         * 없다). 그래서 그냥 던지지 않고 log.error로 남겨 알림이 가게 한다.
         *
         * findByIdForUpdate로 잠근다 — PAYMENT_PENDING 만료 배치(order.internal.batch.
         * PendingOrderExpirationService)가 같은 주문을 동시에 CANCELED로 확정하려 할 수 있어서,
         * Order 행 자체를 잠가 둘 중 하나만 먼저 끝나게 한다(OrderRepository.findByIdForUpdate 참고).
         */
        Order order = orderRepository.findByIdForUpdate(event.orderId())
                .orElseThrow(() -> {
                    log.error("event=ORDER_NOT_FOUND_FOR_PAYMENT_APPROVED orderId={} paymentId={}",
                            event.orderId(), event.paymentId());
                    return new IllegalStateException(
                            "결제 승인된 주문을 찾을 수 없습니다. orderId=" + event.orderId());
                });

        /*
         * [2026-09-06 KST] 만료 배치가 이 주문을 먼저 CANCELED로 확정한 뒤에 뒤늦은 PG 승인이 도착한
         * 경우다. 재고는 이미 release()로 풀려서 다른 주문에 재배분됐을 수 있으므로 주문을 다시 PAID로
         * 되돌리는 건 안전하지 않다 — order는 그대로 CANCELED로 두고 건드리지 않는다. 대신 PG는 실제로
         * 승인했다(돈이 이미 나갔다)는 사실을 놓치면 안 되므로 ERROR로 남긴다.
         * TODO: 자동 환불 로직 추가 — PaymentApi에 환불 계약(예: cancelPayment)이 생기면 여기서 바로
         * 호출하도록 바꾼다. 지금은 그 계약이 없어(주문 인수인계 문서에도 후속 브랜치로 명시) 사람이
         * 이 로그를 보고 수동 환불해야 한다.
         */
        if (order.getStatus() == OrderStatus.CANCELED) {
            log.error("event=PAYMENT_APPROVED_AFTER_ORDER_CANCELED orderId={} paymentId={} amount={}",
                    order.getId(), event.paymentId(), order.getTotalAmount());
            return;
        }

        try {
            order.markPaid();
        } catch (IllegalStateException e) {
            // CANCELED 외에 이론상 도달 불가능해야 하는 다른 상태 — 3번과 같은 이유로 던지기 전에 남긴다.
            log.error("event=ORDER_MARK_PAID_FAILED orderId={} paymentId={} status={}",
                    order.getId(), event.paymentId(), order.getStatus(), e);
            throw e;
        }

        // 명령성 상태 변화 로그 — PII/토큰/pgTid 없이 orderId/금액만 남긴다.
        log.info("event=order_paid orderId={} amount={}", order.getId(), order.getTotalAmount());

        List<Long> orderItemIds = orderItemRepository.findAllByOrderIdOrderByIdAsc(order.getId()).stream()
                .map(OrderItem::getId)
                .toList();
        stockApi.confirm(new StockOrderItemsRequest(order.getId(), orderItemIds));
    }

    /*
     * [2026-09-05 19:13 KST] 결제가 최종 실패(FAILED)했을 때 payment 도메인이 발행한 이벤트를
     * 받아 주문을 취소하고 재고 예약을 해제한다. onPaymentApproved()와 대칭인 보상 흐름이다.
     *
     * v1: order/payment가 둘 다 L2라 서로 직접 못 부르는 제약 때문에 지금은 common.event를 통한
     * 발행/구독으로 풀었다(docs/order-create-foundation.md 참고). R02(order-payment 도메인 경계
     * ADR)가 팀 논의를 거쳐 결정되면 이 연결 방식 자체를 다시 봐야 한다 — 지금은 R01(결제 실패·
     * 미확정·복구 상태 머신)을 먼저 끝내기 위한 v1이다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional
    public void onPaymentFailed(OrderPaymentFailedEvent event) {
        /*
         * onPaymentApproved()와 같은 이유로 정상 흐름에서는 못 일어나는 케이스다 — 로그 없이 던지지
         * 않는다. findByIdForUpdate로 잠그는 이유도 onPaymentApproved()와 같다(만료 배치와의 경합 방지).
         */
        Order order = orderRepository.findByIdForUpdate(event.orderId())
                .orElseThrow(() -> {
                    log.error("event=ORDER_NOT_FOUND_FOR_PAYMENT_FAILED orderId={} paymentId={}",
                            event.orderId(), event.paymentId());
                    return new IllegalStateException(
                            "결제 실패 처리할 주문을 찾을 수 없습니다. orderId=" + event.orderId());
                });

        /*
         * [2026-09-06 KST] 만료 배치가 이미 CANCELED로 확정해 놓은 주문에 뒤늦게 거절/타임아웃 결과가
         * 도착하는 경우다. onPaymentApproved()와 달리 여기는 별도 분기가 필요 없다 — 두 결론이 같은
         * 방향(취소)이라 Order.cancel()의 멱등 가드(이미 CANCELED면 조용히 리턴)와
         * StockReservationService.release()의 RESERVED 조건부 조회(이미 RELEASED면 대상에서 빠짐)가
         * 그대로 안전하게 흡수한다.
         */
        try {
            order.cancel();
        } catch (IllegalStateException e) {
            // PAYMENT_PENDING/CANCELED 외에 이론상 도달 불가능해야 하는 다른 상태 — 마찬가지로 남긴다.
            log.error("event=ORDER_CANCEL_FAILED orderId={} paymentId={} status={}",
                    order.getId(), event.paymentId(), order.getStatus(), e);
            throw e;
        }

        // 명령성 상태 변화 로그 — PII/토큰/pgTid 없이 orderId/사유만 남긴다.
        log.info("event=order_canceled orderId={} reason={}", order.getId(), event.reason());

        List<Long> orderItemIds = orderItemRepository.findAllByOrderIdOrderByIdAsc(order.getId()).stream()
                .map(OrderItem::getId)
                .toList();
        stockApi.release(new StockOrderItemsRequest(order.getId(), orderItemIds));
    }
}
