package com.freshmarket.order.internal.service;

import com.freshmarket.common.event.OrderPaymentRequestedEvent;
import com.freshmarket.order.internal.entity.OrderPaymentRequestOutbox;
import com.freshmarket.order.internal.repository.OrderPaymentRequestOutboxRepository;
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

    private void dispatchOne(OrderPaymentRequestOutbox outbox) {
        try {
            eventPublisher.publishEvent(new OrderPaymentRequestedEvent(outbox.getOrderId(), outbox.getAmount()));
            recordService.recordDispatched(outbox.getId());
        } catch (RuntimeException e) {
            log.error("event=ORDER_PAYMENT_REQUEST_OUTBOX_DISPATCH_FAILED outboxId={} orderId={}",
                    outbox.getId(), outbox.getOrderId(), e);
        }
    }
}
