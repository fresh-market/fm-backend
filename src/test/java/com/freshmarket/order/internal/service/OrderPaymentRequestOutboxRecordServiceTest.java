package com.freshmarket.order.internal.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.order.internal.entity.OrderPaymentRequestOutbox;
import com.freshmarket.order.internal.repository.OrderPaymentRequestOutboxRepository;
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
class OrderPaymentRequestOutboxRecordServiceTest {

    @Mock
    private OrderPaymentRequestOutboxRepository outboxRepository;

    @Test
    void dispatch_성공_사실을_기록한다() {
        OrderPaymentRequestOutbox outbox = OrderPaymentRequestOutbox.pending(100L, 38_700);
        ReflectionTestUtils.setField(outbox, "id", 10L);
        when(outboxRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(outbox));
        OrderPaymentRequestOutboxRecordService sut = new OrderPaymentRequestOutboxRecordService(outboxRepository,
                Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC));

        sut.recordDispatched(10L);

        verify(outboxRepository).findByIdForUpdate(10L);
    }
}
