package com.freshmarket.payment.internal.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.payment.internal.entity.PaymentResultOutbox;
import com.freshmarket.payment.internal.repository.PaymentResultOutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentResultOutboxRecordServiceTest {

    @Mock
    private PaymentResultOutboxRepository outboxRepository;

    @Test
    void dispatch_성공_사실을_기록한다() {
        PaymentResultOutbox outbox = PaymentResultOutbox.approved(900L, 100L,
                java.time.LocalDateTime.of(2026, 9, 6, 10, 0));
        ReflectionTestUtils.setField(outbox, "id", 10L);
        when(outboxRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(outbox));
        PaymentResultOutboxRecordService sut = new PaymentResultOutboxRecordService(outboxRepository,
                Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC));

        sut.recordDispatched(10L);

        verify(outboxRepository).findByIdForUpdate(10L);
    }
}
