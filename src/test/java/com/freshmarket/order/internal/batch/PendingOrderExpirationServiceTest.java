package com.freshmarket.order.internal.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.order.internal.entity.Order;
import com.freshmarket.order.internal.entity.OrderStatus;
import com.freshmarket.order.internal.repository.OrderRepository;
import com.freshmarket.order.internal.service.OrderExpirationTransactionService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PendingOrderExpirationServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderExpirationTransactionService orderExpirationTransactionService;

    private PendingOrderExpirationService sut;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneId.of("UTC"));
        sut = new PendingOrderExpirationService(orderRepository, orderExpirationTransactionService, clock, 60L);
    }

    @Test
    void 커서로_페이지를_넘기며_대상_전부를_만료시킨다() {
        Order order1 = orderWithId(1L);
        Order order2 = orderWithId(2L);
        when(orderRepository.findByStatusAndIdGreaterThanAndUpdatedAtBeforeOrderByIdAsc(
                eq(OrderStatus.PAYMENT_PENDING), eq(0L), any(), any()))
                .thenReturn(List.of(order1, order2));
        when(orderRepository.findByStatusAndIdGreaterThanAndUpdatedAtBeforeOrderByIdAsc(
                eq(OrderStatus.PAYMENT_PENDING), eq(2L), any(), any()))
                .thenReturn(List.of());

        sut.expirePendingOrders();

        verify(orderExpirationTransactionService).expireIfStillPending(1L);
        verify(orderExpirationTransactionService).expireIfStillPending(2L);
    }

    /*
     * 한 건이 실패(락 대기 타임아웃 등)해도 나머지 대상은 계속 처리돼야 한다 —
     * PaymentReconciliationService.reconcileOne과 같은 이유.
     */
    @Test
    void 한_건이_실패해도_나머지_대상은_계속_처리한다() {
        Order order1 = orderWithId(1L);
        Order order2 = orderWithId(2L);
        when(orderRepository.findByStatusAndIdGreaterThanAndUpdatedAtBeforeOrderByIdAsc(
                eq(OrderStatus.PAYMENT_PENDING), eq(0L), any(), any()))
                .thenReturn(List.of(order1, order2));
        when(orderRepository.findByStatusAndIdGreaterThanAndUpdatedAtBeforeOrderByIdAsc(
                eq(OrderStatus.PAYMENT_PENDING), eq(2L), any(), any()))
                .thenReturn(List.of());
        doThrow(new IllegalStateException("잠금 대기 시간 초과"))
                .when(orderExpirationTransactionService).expireIfStillPending(1L);

        sut.expirePendingOrders();

        verify(orderExpirationTransactionService).expireIfStillPending(1L);
        verify(orderExpirationTransactionService).expireIfStillPending(2L);
    }

    private Order orderWithId(Long id) {
        Order order = Order.place(1L, String.valueOf(id), 35_700, 0,
                null, null, 0, 3_000, 38_700, "홍길동", "01012345678", "06234",
                "서울 강남구 테헤란로 1", null, LocalDateTime.of(2026, 8, 21, 12, 0));
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }
}
