package com.freshmarket.payment.internal.service;

import com.freshmarket.common.event.OrderPaymentApprovedEvent;
import com.freshmarket.common.event.OrderPaymentFailedEvent;
import com.freshmarket.payment.internal.entity.PaymentResultOutbox;
import com.freshmarket.payment.internal.entity.PaymentResultOutboxType;
import com.freshmarket.payment.internal.repository.PaymentResultOutboxRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** Payment의 확정 결과를 order에 at-least-once로 전달한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentResultOutboxDispatchService {

    private static final int PAGE_SIZE = 100;

    private final PaymentResultOutboxRepository outboxRepository;
    private final PaymentResultOutboxRecordService recordService;
    private final ApplicationEventPublisher eventPublisher;

    public void dispatchForPayment(Long paymentId) {
        outboxRepository.findByPaymentIdAndDispatchedFalse(paymentId).ifPresent(this::dispatchOne);
    }

    public void dispatchPending() {
        Long afterId = 0L;
        List<PaymentResultOutbox> page;
        do {
            page = outboxRepository.findByDispatchedFalseAndIdGreaterThanOrderByIdAsc(
                    afterId, PageRequest.of(0, PAGE_SIZE));
            for (PaymentResultOutbox outbox : page) {
                dispatchOne(outbox);
                afterId = outbox.getId();
            }
        } while (!page.isEmpty());
    }

    private void dispatchOne(PaymentResultOutbox outbox) {
        try {
            if (outbox.getEventType() == PaymentResultOutboxType.APPROVED) {
                eventPublisher.publishEvent(new OrderPaymentApprovedEvent(
                        outbox.getOrderId(), outbox.getPaymentId(), outbox.getPaidAt()));
            } else {
                eventPublisher.publishEvent(new OrderPaymentFailedEvent(
                        outbox.getOrderId(), outbox.getPaymentId(), outbox.getFailureReason()));
            }
            recordService.recordDispatched(outbox.getId());
        } catch (RuntimeException e) {
            log.error("event=PAYMENT_RESULT_OUTBOX_DISPATCH_FAILED outboxId={} paymentId={} orderId={}",
                    outbox.getId(), outbox.getPaymentId(), outbox.getOrderId(), e);
        }
    }
}
