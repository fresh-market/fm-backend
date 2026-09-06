package com.freshmarket.order.internal.service;

import com.freshmarket.order.internal.repository.OrderPaymentRequestOutboxRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderPaymentRequestOutboxDeliveryService {

    private final OrderPaymentRequestOutboxRepository outboxRepository;
    private final Clock clock;

    @Transactional
    public void markDispatched(Long outboxId) {
        outboxRepository.findByIdForUpdate(outboxId)
                .ifPresent(outbox -> outbox.markDispatched(LocalDateTime.now(clock)));
    }
}
