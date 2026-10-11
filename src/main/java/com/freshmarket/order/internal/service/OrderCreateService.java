package com.freshmarket.order.internal.service;

import com.freshmarket.common.event.OrderPaymentApprovedEvent;
import com.freshmarket.common.event.OrderPaymentFailedEvent;
import com.freshmarket.common.event.OrderPaymentRefundRequestedEvent;
import com.freshmarket.order.internal.PendingOrderResult;
import com.freshmarket.order.internal.dto.OrderCreateRequest;
import com.freshmarket.order.internal.dto.OrderCreateResponse;
import com.freshmarket.order.internal.entity.Order;
import com.freshmarket.order.internal.entity.OrderItem;
import com.freshmarket.order.internal.repository.OrderItemRepository;
import com.freshmarket.order.internal.repository.OrderRepository;
import com.freshmarket.stock.StockApi;
import com.freshmarket.stock.StockOrderItemsRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/*
 * 장바구니 -> 주문 생성(POST /v1/orders)의 공개 진입점이다. 클래스/메서드 어디에도 @Transactional을
 * 달지 않는다 — 아래 세 단계가 각자 자기 트랜잭션을 갖고, 결제 요청은 열린 DB 트랜잭션이 전혀 없는
 * 상태에서 나간다. 이건 팀이 미리 정해둔 경계다(주문 인수인계 문서 5장 "PG 호출과 DB 트랜잭션 경계"):
 *
 *   a. orderPendingCreationCoordinatorService.createPendingOrder(...) — 내부의
 *      OrderPendingCreationService 짧은 트랜잭션으로 주문/주문상품 저장, 재고 예약, 장바구니 정리까지
 *      끝내고 커밋한 뒤 돌아온다. requestId 동시 충돌이면 커밋된 기존 주문을 다시 읽어 수렴한다.
 *   b. (여기, 트랜잭션 밖) order outbox dispatch — payment.domain의 리스너가 공용 이벤트를 받아
 *      PaymentApi.preparePayment를 불러 PENDING 결제 행만 만든다(자세한 이유는 그 이벤트 클래스
 *      주석: order/payment 둘 다 L2라 서로 직접 못 부른다). [2026-09-27 KST] 토스 연동 전에는
 *      이 지점에서 곧바로 PG 승인 호출까지 동기로 이어졌지만, 이제는 PENDING 준비로 끝난다 — 실제
 *      PG 승인은 프론트가 토스 결제창 인증을 마친 뒤 별도로 부르는 확정(confirm) API
 *      (PaymentConfirmController → PaymentConfirmationService)에서 일어난다. 그래도 "PG 호출을
 *      열린 트랜잭션 밖으로 빼는 게 이 구조의 핵심이다"는 그대로 유효하다 — 그 PG 호출이 이제
 *      이 메서드가 아니라 confirm 요청 시점에 일어날 뿐이다.
 *   c. onPaymentApproved/onPaymentFailed(아래) — 결제 결과가 확정되면 이벤트 체인 끝에서 새로
 *      짧은 트랜잭션을 연다.
 *
 * a단계를 이 클래스 안의 @Transactional 메서드로 두지 않고 별도 빈(OrderPendingCreationService)
 * 으로 뺀 이유: 같은 빈 안에서 this.메서드()로 자기 자신을 호출하면 스프링 프록시를 안 거쳐
 * @Transactional이 조용히 무시된다(자기호출 함정) — TransactionTemplate으로 직접 경계를 긋는
 * 방법도 있지만, 이 프로젝트는 지금까지 전부 선언적 @Transactional만 써왔어서(TransactionTemplate
 * 쓰는 곳이 없다) 스타일을 맞추는 쪽을 택했다.
 *
 * 결제 결과도 payment outbox가 payment 커밋 뒤에 발행한다. 따라서 이 리스너는 평범한
 * @EventListener로 받고 REQUIRES_NEW에서 order/stock을 함께 확정한다. 처리 실패는 publisher에
 * 전달되어 outbox가 미완료 상태로 남고, batch가 다시 전달한다.
 *
 * [2026-09-27 KST] onPaymentApproved()의 실제 DB 트랜잭션은 OrderPaymentApprovalTransactionService로
 * 옮겼다 — 이미 CANCELED인 주문에 뒤늦게 승인이 온 경우 자동 환불을 위해
 * OrderPaymentRefundRequestedEvent를 발행해야 하는데, 그 이벤트를 처리하는 쪽(payment 도메인)이
 * 결국 PaymentGateway.cancel()이라는 PG 호출로 이어지므로 onPaymentApproved() 자신은 더 이상
 * @Transactional을 걸지 않는다. 이벤트는 그 트랜잭션이 커밋되어 반환된 "뒤"에만 발행한다 — PG
 * 호출이 열린 DB 트랜잭션 안에서 나가면 안 된다는 원칙(위 단락)을 여기서도 그대로 지킨다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCreateService {

    private final OrderPendingCreationCoordinatorService orderPendingCreationCoordinatorService;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockApi stockApi;
    private final OrderPaymentRequestOutboxDispatchService outboxDispatchService;
    private final OrderPaymentApprovalTransactionService orderPaymentApprovalTransactionService;
    private final ApplicationEventPublisher eventPublisher;

    public OrderCreateResponse createOrder(Long memberId, OrderCreateRequest request) {
        PendingOrderResult pending = orderPendingCreationCoordinatorService.createPendingOrder(memberId, request);
        // 새 요청과 requestId 재시도 모두 미전달 outbox만 전송한다. 이미 dispatch된 행은 조회되지 않는다.
        outboxDispatchService.dispatchForOrder(pending.response().orderId());

        return pending.response();
    }

    /*
     * 결제 승인 뒤 payment 도메인이 발행한 이벤트를 받아 주문을 PAID로 바꾸고 재고를 확정한다.
     * createOrder()의 a단계와는 별개의 새 트랜잭션이다(위 클래스 주석의 c단계) — 다만 그 트랜잭션은
     * 이제 이 메서드가 아니라 OrderPaymentApprovalTransactionService가 연다(클래스 주석 참고).
     *
     * [2026-09-27 KST] 자동 환불: applyApproval()이 "이미 CANCELED인 주문에 뒤늦게 승인이 왔다"고
     * 알려오면(true), 그 트랜잭션이 이미 커밋된 뒤이므로 안전하게 OrderPaymentRefundRequestedEvent를
     * 발행한다. payment 도메인이 이를 받아 PaymentGateway.cancel()로 실제 환불을 시도한다 — 그 호출이
     * 실패하면 예외가 여기까지 그대로 전파되어 이 메서드를 호출한 PaymentResultOutboxDispatchService
     * 쪽에서 outbox를 미완료로 남기고, 다음 배치 주기에 OrderPaymentApprovedEvent가 다시 전달되어
     * 환불도 자동으로 재시도된다.
     */
    @EventListener
    public void onPaymentApproved(OrderPaymentApprovedEvent event) {
        boolean refundNeeded = orderPaymentApprovalTransactionService.applyApproval(event.orderId(), event.paymentId());
        if (refundNeeded) {
            eventPublisher.publishEvent(new OrderPaymentRefundRequestedEvent(
                    event.orderId(), event.paymentId(), "이미 취소된 주문에 뒤늦게 결제가 승인됨"));
        }
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
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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

        List<OrderItem> orderItems = orderItemRepository.findAllByOrderIdOrderByIdAsc(order.getId());
        orderItems.forEach(OrderItem::cancel);
        List<Long> orderItemIds = orderItems.stream()
                .map(OrderItem::getId)
                .toList();
        stockApi.release(new StockOrderItemsRequest(order.getId(), orderItemIds));
    }
}
