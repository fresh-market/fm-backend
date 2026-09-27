package com.freshmarket.order.internal.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.common.event.OrderPaymentRequestedEvent;
import com.freshmarket.order.internal.entity.Order;
import com.freshmarket.order.internal.entity.OrderPaymentRequestOutbox;
import com.freshmarket.order.internal.repository.OrderPaymentRequestOutboxRepository;
import com.freshmarket.order.internal.repository.OrderRepository;
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
    private OrderRepository orderRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private OrderPaymentRequestOutboxDispatchService sut;

    @BeforeEach
    void setUp() {
        sut = new OrderPaymentRequestOutboxDispatchService(outboxRepository, recordService, orderRepository, eventPublisher);
    }

    @Test
    void 주문의_미전달_결제요청을_발행하고_완료처리한다() {
        OrderPaymentRequestOutbox outbox = outbox(10L, 100L);
        when(outboxRepository.findByOrderIdAndDispatchedFalse(100L)).thenReturn(Optional.of(outbox));
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order(100L, 7L)));

        sut.dispatchForOrder(100L);

        verify(eventPublisher).publishEvent(new OrderPaymentRequestedEvent(100L, 7L, 38_700));
        verify(recordService).recordDispatched(10L);
    }

    @Test
    void 발행이_실패하면_완료처리하지_않아_재시도_대상으로_남긴다() {
        OrderPaymentRequestOutbox outbox = outbox(10L, 100L);
        when(outboxRepository.findByOrderIdAndDispatchedFalse(100L)).thenReturn(Optional.of(outbox));
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order(100L, 7L)));
        doThrow(new IllegalStateException("payment unavailable")).when(eventPublisher).publishEvent(any());

        sut.dispatchForOrder(100L);

        verify(recordService, never()).recordDispatched(any());
    }

    @Test
    void 대상_주문을_찾을_수_없으면_완료처리하지_않아_재시도_대상으로_남긴다() {
        OrderPaymentRequestOutbox outbox = outbox(10L, 100L);
        when(outboxRepository.findByOrderIdAndDispatchedFalse(100L)).thenReturn(Optional.of(outbox));
        when(orderRepository.findById(100L)).thenReturn(Optional.empty());

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
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order(100L, 7L)));

        sut.dispatchPending();

        verify(eventPublisher).publishEvent(new OrderPaymentRequestedEvent(100L, 7L, 38_700));
    }

    private OrderPaymentRequestOutbox outbox(Long id, Long orderId) {
        OrderPaymentRequestOutbox outbox = OrderPaymentRequestOutbox.pending(orderId, 38_700);
        ReflectionTestUtils.setField(outbox, "id", id);
        return outbox;
    }

    private Order order(Long orderId, Long memberId) {
        Order order = mock(Order.class);
        lenient().when(order.getMemberId()).thenReturn(memberId);
        return order;
    }
}
