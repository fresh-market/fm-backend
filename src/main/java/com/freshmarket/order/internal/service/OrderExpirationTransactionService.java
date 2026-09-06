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
import org.springframework.transaction.annotation.Transactional;

/*
 * [2026-09-06 KST] PAYMENT_PENDING 만료 배치(order.internal.batch.PendingOrderExpirationService)가
 * 후보 하나씩을 잠그고 취소하는 실제 트랜잭션 단위다. 배치 쪽 루프 메서드 안에서 this.로 이 메서드를
 * 부르면 스프링 프록시를 안 거쳐 @Transactional이 조용히 무시되므로(자기호출 함정 —
 * OrderCreateService가 OrderPendingCreationService를 별도 빈으로 뺀 것과 같은 이유), 처음부터
 * 별도 빈으로 둔다.
 *
 * orderRepository.findByIdForUpdate로 잠근 뒤 상태를 다시 확인한다 — 배치의 커서 조회는 잠그지
 * 않은 조회라, 그 사이 OrderCreateService.onPaymentApproved()/onPaymentFailed()가 먼저 이 주문을
 * 처리했을 수 있다. 그러면 더 이상 PAYMENT_PENDING이 아니므로 손대지 않고 조용히 넘어간다 —
 * 경합에서 "진 쪽"이 아니라, 애초에 이 배치가 다룰 대상이 더 이상 아니라는 뜻이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExpirationTransactionService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockApi stockApi;

    @Transactional
    public void expireIfStillPending(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> {
                    log.error("event=ORDER_NOT_FOUND_FOR_EXPIRATION orderId={}", orderId);
                    return new IllegalStateException("만료 대상 주문을 찾을 수 없습니다. orderId=" + orderId);
                });

        if (order.getStatus() != OrderStatus.PAYMENT_PENDING) {
            log.info("event=ORDER_EXPIRATION_SKIPPED_ALREADY_RESOLVED orderId={} status={}",
                    order.getId(), order.getStatus());
            return;
        }

        order.cancel();
        log.info("event=order_canceled orderId={} reason=payment_pending_expired", order.getId());

        List<Long> orderItemIds = orderItemRepository.findAllByOrderIdOrderByIdAsc(order.getId()).stream()
                .map(OrderItem::getId)
                .toList();
        stockApi.release(new StockOrderItemsRequest(order.getId(), orderItemIds));
    }
}
