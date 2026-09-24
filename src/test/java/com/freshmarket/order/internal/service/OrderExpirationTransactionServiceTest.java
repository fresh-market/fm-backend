package com.freshmarket.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.order.internal.entity.Order;
import com.freshmarket.order.internal.entity.OrderItem;
import com.freshmarket.order.internal.entity.OrderItemStatus;
import com.freshmarket.order.internal.entity.OrderStatus;
import com.freshmarket.order.internal.repository.OrderItemRepository;
import com.freshmarket.order.internal.repository.OrderRepository;
import com.freshmarket.stock.StockApi;
import com.freshmarket.stock.StockOrderItemsRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderExpirationTransactionServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private StockApi stockApi;

    private OrderExpirationTransactionService sut;

    @BeforeEach
    void setUp() {
        sut = new OrderExpirationTransactionService(orderRepository, orderItemRepository, stockApi);
    }

    @Test
    void PAYMENT_PENDING_상태의_주문을_취소하고_재고를_해제한다() {
        Order order = order();
        ReflectionTestUtils.setField(order, "id", 100L);
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        OrderItem item = orderItem(100L, 501L);
        when(orderItemRepository.findAllByOrderIdOrderByIdAsc(100L)).thenReturn(List.of(item));

        sut.expireIfStillPending(100L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(item.getItemStatus()).isEqualTo(OrderItemStatus.CANCELED);
        verify(stockApi).release(new StockOrderItemsRequest(100L, List.of(501L)));
    }

    /*
     * [2026-09-06 KST] 배치의 커서 조회(잠금 없음)와 이 메서드의 findByIdForUpdate 호출 사이에
     * onPaymentApproved()/onPaymentFailed()가 먼저 이 주문을 처리했을 수 있다 — 이미
     * PAYMENT_PENDING이 아니면 손대지 않고 조용히 넘어가야 한다.
     */
    @Test
    void 이미_다른_상태로_해소된_주문은_건드리지_않는다() {
        Order order = order();
        ReflectionTestUtils.setField(order, "id", 100L);
        order.markPaid();
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        sut.expireIfStillPending(100L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(stockApi, never()).release(any());
        verify(orderItemRepository, never()).findAllByOrderIdOrderByIdAsc(any());
    }

    @Test
    void 만료_대상_주문을_찾을_수_없으면_예외를_던진다() {
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.expireIfStillPending(100L))
                .isInstanceOf(IllegalStateException.class);

        verify(stockApi, never()).release(any());
    }

    private Order order() {
        return Order.place(1L, "100", 35_700, 0,
                null, null, 0, 3_000, 38_700, "홍길동", "01012345678", "06234",
                "서울 강남구 테헤란로 1", null, LocalDateTime.of(2026, 8, 21, 12, 0));
    }

    private OrderItem orderItem(Long orderId, Long id) {
        OrderItem item = OrderItem.place(orderId, 20L, "감귤 1kg", "1kg", 12_900, 2,
                1L, null, null, 0, 0, OrderItemStatus.ORDERED);
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }
}
