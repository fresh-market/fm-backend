package com.freshmarket.payment.internal.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.common.event.OrderPaymentApprovedEvent;
import com.freshmarket.common.event.OrderPaymentFailedEvent;
import com.freshmarket.payment.internal.entity.PaymentResultOutbox;
import com.freshmarket.payment.internal.repository.PaymentResultOutboxRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentResultOutboxDispatchServiceTest {

    @Mock
    private PaymentResultOutboxRepository outboxRepository;
    @Mock
    private PaymentResultOutboxDeliveryService deliveryService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private PaymentResultOutboxDispatchService sut;

    @BeforeEach
    void setUp() {
        sut = new PaymentResultOutboxDispatchService(outboxRepository, deliveryService, eventPublisher);
    }

    @Test
    void 승인_결과를_발행하고_완료처리한다() {
        LocalDateTime paidAt = LocalDateTime.of(2026, 9, 6, 10, 0);
        PaymentResultOutbox outbox = approvedOutbox(10L, paidAt);
        when(outboxRepository.findByPaymentIdAndDispatchedFalse(900L)).thenReturn(Optional.of(outbox));

        sut.dispatchForPayment(900L);

        verify(eventPublisher).publishEvent(new OrderPaymentApprovedEvent(100L, 900L, paidAt));
        verify(deliveryService).markDispatched(10L);
    }

    @Test
    void 실패_결과를_발행한다() {
        PaymentResultOutbox outbox = PaymentResultOutbox.failed(900L, 100L, "한도 초과");
        ReflectionTestUtils.setField(outbox, "id", 10L);
        when(outboxRepository.findByPaymentIdAndDispatchedFalse(900L)).thenReturn(Optional.of(outbox));

        sut.dispatchForPayment(900L);

        verify(eventPublisher).publishEvent(new OrderPaymentFailedEvent(100L, 900L, "한도 초과"));
    }

    @Test
    void 발행이_실패하면_완료처리하지_않는다() {
        PaymentResultOutbox outbox = approvedOutbox(10L, LocalDateTime.of(2026, 9, 6, 10, 0));
        when(outboxRepository.findByPaymentIdAndDispatchedFalse(900L)).thenReturn(Optional.of(outbox));
        doThrow(new IllegalStateException("order unavailable")).when(eventPublisher).publishEvent(any());

        sut.dispatchForPayment(900L);

        verify(deliveryService, never()).markDispatched(any());
    }

    @Test
    void 미전달_결과를_커서로_전부_dispatch한다() {
        PaymentResultOutbox outbox = approvedOutbox(10L, LocalDateTime.of(2026, 9, 6, 10, 0));
        when(outboxRepository.findByDispatchedFalseAndIdGreaterThanOrderByIdAsc(eq(0L), any()))
                .thenReturn(List.of(outbox));
        when(outboxRepository.findByDispatchedFalseAndIdGreaterThanOrderByIdAsc(eq(10L), any()))
                .thenReturn(List.of());

        sut.dispatchPending();

        verify(eventPublisher).publishEvent(any(OrderPaymentApprovedEvent.class));
    }

    private PaymentResultOutbox approvedOutbox(Long id, LocalDateTime paidAt) {
        PaymentResultOutbox outbox = PaymentResultOutbox.approved(900L, 100L, paidAt);
        ReflectionTestUtils.setField(outbox, "id", id);
        return outbox;
    }
}
