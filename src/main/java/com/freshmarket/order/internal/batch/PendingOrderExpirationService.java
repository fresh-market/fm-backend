package com.freshmarket.order.internal.batch;

import com.freshmarket.order.internal.entity.Order;
import com.freshmarket.order.internal.entity.OrderStatus;
import com.freshmarket.order.internal.repository.OrderRepository;
import com.freshmarket.order.internal.service.OrderExpirationTransactionService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/*
 * [2026-09-06 KST] PG에 물어볼 거래 자체가 없을 만큼 오래 PAYMENT_PENDING에 멈춘 주문을 순수 TTL
 * 기준으로 취소한다. payment.internal.batch.PaymentReconciliationService와는 서로 다른 문제를
 * 푼다 — 그쪽은 "PG는 뭔가 알고 있는데 우리가 그 결과를 모르는" 경우를 PG 재조회로 확인하고, 이건
 * "PG한테 물어봐도 답이 없는"(요청 자체가 안 갔거나 사용자가 결제를 끝까지 안 한) 경우를 시간으로
 * 포기하는 것이다. orderdevelopmenthandoff.txt 9번 항목 참고.
 *
 * PendingProductImageCleanupService/PaymentReconciliationService와 같은 이유로 배치 어댑터라
 * internal.service(커버리지 100% 대상)가 아니라 internal.batch 패키지에 둔다. 실제 잠금·전이는
 * 별도 빈인 OrderExpirationTransactionService(order.internal.service)에 위임한다 — 이 클래스
 * 자체는 @Transactional을 달지 않는다(자기호출 함정, 그 클래스 주석 참고).
 *
 * 이 배치는 Payment 쪽을 전혀 건드리지 않는다. Order만 CANCELED로 확정하고 재고만 release한다 —
 * 그 뒤 Payment가 뒤늦게 PAID로 확정되는 경우는 OrderCreateService.onPaymentApproved()가 이미
 * "order가 CANCELED면 되돌리지 않고 환불 필요 로그만 남긴다"는 정책으로 안전하게 흡수하므로, 여기서
 * Payment 상태까지 미리 손대지 않아도 두 배치가 서로의 존재를 몰라도 안전하게 맞물린다.
 */
@Slf4j
@Service
public class PendingOrderExpirationService {

    private static final int PAGE_SIZE = 100;

    private final OrderRepository orderRepository;
    private final OrderExpirationTransactionService orderExpirationTransactionService;
    private final Clock clock;
    private final Duration gracePeriod;

    public PendingOrderExpirationService(OrderRepository orderRepository,
            OrderExpirationTransactionService orderExpirationTransactionService, Clock clock,
            @Value("${order.payment-expiration.grace-minutes:60}") long graceMinutes) {
        this.orderRepository = orderRepository;
        this.orderExpirationTransactionService = orderExpirationTransactionService;
        this.clock = clock;
        this.gracePeriod = Duration.ofMinutes(graceMinutes);
    }

    public void expirePendingOrders() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(gracePeriod);
        Long afterId = 0L;
        List<Order> page;
        Pageable pageable = PageRequest.of(0, PAGE_SIZE);
        do {
            page = orderRepository.findByStatusAndIdGreaterThanAndUpdatedAtBeforeOrderByIdAsc(
                    OrderStatus.PAYMENT_PENDING, afterId, cutoff, pageable);
            for (Order candidate : page) {
                expireOne(candidate.getId());
                afterId = candidate.getId();
            }
        } while (!page.isEmpty());
    }

    /*
     * 한 건의 실패(락 대기 타임아웃, 예상 못한 예외 등)가 이번 주기의 나머지 대상까지 멈추지 않는다 —
     * PaymentReconciliationService.reconcileOne과 같은 이유로, 실패한 건은 상태를 그대로 두어 다음
     * 주기에 다시 대상이 된다.
     */
    private void expireOne(Long orderId) {
        try {
            orderExpirationTransactionService.expireIfStillPending(orderId);
        } catch (RuntimeException e) {
            log.error("event=ORDER_EXPIRATION_FAILED orderId={}", orderId, e);
        }
    }
}
