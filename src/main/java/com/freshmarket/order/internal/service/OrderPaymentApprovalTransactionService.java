package com.freshmarket.order.internal.service;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/*
 * [2026-09-27 KST] OrderCreateService.onPaymentApproved()에서 DB 트랜잭션 부분만 떼어낸 빈이다.
 * OrderExpirationTransactionService가 배치 루프 메서드에서 떨어져 나온 것과 같은 이유다 — 같은 빈
 * 안에서 this.메서드()로 부르면 스프링 프록시를 안 거쳐 @Transactional이 조용히 무시된다(자기호출
 * 함정).
 *
 * onPaymentApproved()가 이 트랜잭션을 자기 메서드에 직접 걸지 않고 이 빈에 위임하도록 바꾼 이유는
 * 자기호출 함정과는 별개다 — 이미 CANCELED인 주문에 뒤늦게 승인이 온 경우(PAYMENT_PENDING 만료
 * 배치가 먼저 취소한 뒤 뒤늦게 PG 승인이 도착한 경우) 자동 환불을 위해 결국
 * PaymentGateway.cancel()이라는 PG 호출로 이어져야 하는데, 그 호출이 이 트랜잭션이 열려 있는
 * 동안 일어나면 안 된다(PG 호출은 항상 열린 DB 트랜잭션 밖에서 — 주문 인수인계 문서 5장). 그래서
 * 이 메서드는 "환불이 필요한가"만 boolean으로 알리고 자기 트랜잭션을 커밋한 뒤 반환하고, 실제 환불
 * 요청 이벤트(OrderPaymentRefundRequestedEvent) 발행은 이 메서드가 반환된 뒤
 * onPaymentApproved()가 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPaymentApprovalTransactionService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockApi stockApi;

    /**
     * @return 이미 CANCELED였던 주문에 뒤늦게 승인이 도착해 환불이 필요하면 true, 정상적으로 PAID로
     * 확정했으면 false.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean applyApproval(Long orderId, Long paymentId) {
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
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> {
                    log.error("event=ORDER_NOT_FOUND_FOR_PAYMENT_APPROVED orderId={} paymentId={}",
                            orderId, paymentId);
                    return new IllegalStateException(
                            "결제 승인된 주문을 찾을 수 없습니다. orderId=" + orderId);
                });

        /*
         * [2026-09-06 KST] 만료 배치가 이 주문을 먼저 CANCELED로 확정한 뒤에 뒤늦은 PG 승인이 도착한
         * 경우다. 재고는 이미 release()로 풀려서 다른 주문에 재배분됐을 수 있으므로 주문을 다시 PAID로
         * 되돌리는 건 안전하지 않다 — order는 그대로 CANCELED로 두고 건드리지 않는다.
         *
         * [2026-09-27 KST] 전에는 여기서 ERROR 로그만 남기고 사람이 수동 환불해야 했다(TODO 주석
         * 참고). 이제는 true를 돌려줘 onPaymentApproved()가 이 트랜잭션 커밋 뒤
         * OrderPaymentRefundRequestedEvent를 발행하게 한다 — payment 도메인이 그 이벤트를 받아
         * PaymentGateway.cancel()로 자동 환불한다.
         */
        if (order.getStatus() == OrderStatus.CANCELED) {
            log.error("event=PAYMENT_APPROVED_AFTER_ORDER_CANCELED orderId={} paymentId={} amount={}",
                    order.getId(), paymentId, order.getTotalAmount());
            return true;
        }

        try {
            order.markPaid();
        } catch (IllegalStateException e) {
            // CANCELED 외에 이론상 도달 불가능해야 하는 다른 상태 — 위와 같은 이유로 던지기 전에 남긴다.
            log.error("event=ORDER_MARK_PAID_FAILED orderId={} paymentId={} status={}",
                    order.getId(), paymentId, order.getStatus(), e);
            throw e;
        }

        // 명령성 상태 변화 로그 — PII/토큰/pgTid 없이 orderId/금액만 남긴다.
        log.info("event=order_paid orderId={} amount={}", order.getId(), order.getTotalAmount());

        List<Long> orderItemIds = orderItemRepository.findAllByOrderIdOrderByIdAsc(order.getId()).stream()
                .map(OrderItem::getId)
                .toList();
        stockApi.confirm(new StockOrderItemsRequest(order.getId(), orderItemIds));
        return false;
    }
}
