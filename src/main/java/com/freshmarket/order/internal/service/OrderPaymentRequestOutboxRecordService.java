package com.freshmarket.order.internal.service;

import com.freshmarket.order.internal.repository.OrderPaymentRequestOutboxRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 이벤트 소비 성공 사실을 outbox에 별도 트랜잭션으로 기록한다. */
@Service
@RequiredArgsConstructor
public class OrderPaymentRequestOutboxRecordService {

    private final OrderPaymentRequestOutboxRepository outboxRepository;
    private final Clock clock;

    @Transactional
    public void recordDispatched(Long outboxId) {
        outboxRepository.findByIdForUpdate(outboxId)
                .ifPresent(outbox -> outbox.markDispatched(LocalDateTime.now(clock)));
    }
}
