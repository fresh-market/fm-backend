package com.freshmarket.order.internal.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.common.event.OrderPaymentRequestedEvent;
import com.freshmarket.order.internal.entity.OrderPaymentRequestOutbox;
import com.freshmarket.order.internal.repository.OrderPaymentRequestOutboxRepository;
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
class OrderPaymentRequestOutboxDispatchServiceTest {

    @Mock
    private OrderPaymentRequestOutboxRepository outboxRepository;
    @Mock
    private OrderPaymentRequestOutboxRecordService recordService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private OrderPaymentRequestOutboxDispatchService sut;

    @BeforeEach
    void setUp() {
        sut = new OrderPaymentRequestOutboxDispatchService(outboxRepository, recordService, eventPublisher);
    }

    @Test
    void 주문의_미전달_결제요청을_발행하고_완료처리한다() {
        OrderPaymentRequestOutbox outbox = outbox(10L, 100L);
        when(outboxRepository.findByOrderIdAndDispatchedFalse(100L)).thenReturn(Optional.of(outbox));

        sut.dispatchForOrder(100L);

        verify(eventPublisher).publishEvent(new OrderPaymentRequestedEvent(100L, 38_700));
        verify(recordService).recordDispatched(10L);
    }

    @Test
    void 발행이_실패하면_완료처리하지_않아_재시도_대상으로_남긴다() {
        OrderPaymentRequestOutbox outbox = outbox(10L, 100L);
        when(outboxRepository.findByOrderIdAndDispatchedFalse(100L)).thenReturn(Optional.of(outbox));
        doThrow(new IllegalStateException("payment unavailable")).when(eventPublisher).publishEvent(any());

        sut.dispatchForOrder(100L);

        verify(recordService, never()).recordDispatched(any());
    }

    @Test
    void 미전달_행을_커서로_전부_dispatch한다() {
        OrderPaymentRequestOutbox outbox = outbox(10L, 100L);
        when(outboxRepository.findByDispatchedFalseAndIdGreaterThanOrderByIdAsc(eq(0L), any()))
                .thenReturn(List.of(outbox));
        when(outboxRepository.findByDispatchedFalseAndIdGreaterThanOrderByIdAsc(eq(10L), any()))
                .thenReturn(List.of());

        sut.dispatchPending();

        verify(eventPublisher).publishEvent(new OrderPaymentRequestedEvent(100L, 38_700));
    }

    private OrderPaymentRequestOutbox outbox(Long id, Long orderId) {
        OrderPaymentRequestOutbox outbox = OrderPaymentRequestOutbox.pending(orderId, 38_700);
        ReflectionTestUtils.setField(outbox, "id", id);
        return outbox;
    }
}
