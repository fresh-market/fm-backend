package com.freshmarket.payment.internal.service;

import com.freshmarket.payment.internal.repository.PaymentResultOutboxRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentResultOutboxDeliveryService {

    private final PaymentResultOutboxRepository outboxRepository;
    private final Clock clock;

    @Transactional
    public void markDispatched(Long outboxId) {
        outboxRepository.findByIdForUpdate(outboxId)
                .ifPresent(outbox -> outbox.markDispatched(LocalDateTime.now(clock)));
    }
}
