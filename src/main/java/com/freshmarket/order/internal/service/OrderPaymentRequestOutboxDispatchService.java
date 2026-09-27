package com.freshmarket.order.internal.service;

import com.freshmarket.common.event.OrderPaymentRequestedEvent;
import com.freshmarket.order.internal.entity.Order;
import com.freshmarket.order.internal.entity.OrderPaymentRequestOutbox;
import com.freshmarket.order.internal.repository.OrderPaymentRequestOutboxRepository;
import com.freshmarket.order.internal.repository.OrderRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * outbox를 common 이벤트로 전달한다. 이벤트 처리 성공 뒤에만 dispatched를 기록하므로, 실패한 행은
 * 다음 요청 또는 batch에서 재시도된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPaymentRequestOutboxDispatchService {

    private static final int PAGE_SIZE = 100;

    private final OrderPaymentRequestOutboxRepository outboxRepository;
    private final OrderPaymentRequestOutboxRecordService recordService;
    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher eventPublisher;

    public void dispatchForOrder(Long orderId) {
        outboxRepository.findByOrderIdAndDispatchedFalse(orderId).ifPresent(this::dispatchOne);
    }

    public void dispatchPending() {
        Long afterId = 0L;
        List<OrderPaymentRequestOutbox> page;
        do {
            page = outboxRepository.findByDispatchedFalseAndIdGreaterThanOrderByIdAsc(
                    afterId, PageRequest.of(0, PAGE_SIZE));
            for (OrderPaymentRequestOutbox outbox : page) {
                dispatchOne(outbox);
                afterId = outbox.getId();
            }
        } while (!page.isEmpty());
    }

    /*
     * [2026-09-27 KST] outbox 행 자체에는 memberId가 없다(생성 당시엔 필요 없었다) — 이벤트에
     * memberId를 실어 보내야 confirm 시점에 payment가 주문 소유자를 확인할 수 있어서
     * (OrderPaymentRequestedEvent 클래스 주석 참고), 여기서 Order를 다시 조회해 채운다. order
     * 도메인 내부 조회라 L2 경계 규칙에 걸리지 않는다. outbox 테이블에 member_id 컬럼을 추가하는
     * 대신 이 방식을 택했다 — outbox는 이미 있는 order로부터 유도되는 값이라 굳이 또 스냅샷을
     * 늘리지 않기 위해서다.
     */
    private void dispatchOne(OrderPaymentRequestOutbox outbox) {
        try {
            Order order = orderRepository.findById(outbox.getOrderId())
                    .orElseThrow(() -> new IllegalStateException("결제 요청 대상 주문을 찾을 수 없습니다. orderId=" + outbox.getOrderId()));
            eventPublisher.publishEvent(
                    new OrderPaymentRequestedEvent(outbox.getOrderId(), order.getMemberId(), outbox.getAmount()));
            recordService.recordDispatched(outbox.getId());
        } catch (RuntimeException e) {
            log.error("event=ORDER_PAYMENT_REQUEST_OUTBOX_DISPATCH_FAILED outboxId={} orderId={}",
                    outbox.getId(), outbox.getOrderId(), e);
        }
    }
}
