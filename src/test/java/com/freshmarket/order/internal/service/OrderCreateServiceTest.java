package com.freshmarket.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.common.event.OrderPaymentApprovedEvent;
import com.freshmarket.common.event.OrderPaymentRequestedEvent;
import com.freshmarket.order.internal.PendingOrderResult;
import com.freshmarket.order.internal.dto.OrderCreateRequest;
import com.freshmarket.order.internal.dto.OrderCreateResponse;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderCreateServiceTest {

    @Mock
    private OrderPendingCreationService orderPendingCreationService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private StockApi stockApi;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private OrderCreateService sut;

    @BeforeEach
    void setUp() {
        sut = new OrderCreateService(
                orderPendingCreationService, orderRepository, orderItemRepository, stockApi, eventPublisher);
    }

    @Test
    void 새로_생성된_주문이면_결제요청_이벤트를_발행한다() {
        OrderCreateRequest request = request();
        OrderCreateResponse response = new OrderCreateResponse(100L, "100", OrderStatus.PAYMENT_PENDING, 38_700);
        when(orderPendingCreationService.createPendingOrder(1L, request))
                .thenReturn(new PendingOrderResult(response, true));

        OrderCreateResponse result = sut.createOrder(1L, request);

        assertThat(result).isEqualTo(response);
        verify(eventPublisher).publishEvent(new OrderPaymentRequestedEvent(100L, 38_700));
    }

    @Test
    void requestId_재시도로_기존_주문을_돌려주면_결제요청_이벤트를_다시_발행하지_않는다() {
        OrderCreateRequest request = request();
        OrderCreateResponse response = new OrderCreateResponse(100L, "100", OrderStatus.PAID, 38_700);
        when(orderPendingCreationService.createPendingOrder(1L, request))
                .thenReturn(new PendingOrderResult(response, false));

        OrderCreateResponse result = sut.createOrder(1L, request);

        assertThat(result).isEqualTo(response);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void 결제가_승인되면_주문을_PAID로_바꾸고_재고를_확정한다() {
        Order order = order();
        ReflectionTestUtils.setField(order, "id", 100L);
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));
        OrderItem item1 = orderItem(100L, 501L);
        OrderItem item2 = orderItem(100L, 502L);
        when(orderItemRepository.findAllByOrderIdOrderByIdAsc(100L)).thenReturn(List.of(item1, item2));

        sut.onPaymentApproved(new OrderPaymentApprovedEvent(100L, 900L, LocalDateTime.of(2026, 8, 21, 12, 5)));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(stockApi).confirm(new StockOrderItemsRequest(100L, List.of(501L, 502L)));
    }

    @Test
    void 결제_승인된_주문을_찾을_수_없으면_예외를_던진다() {
        when(orderRepository.findById(100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.onPaymentApproved(
                new OrderPaymentApprovedEvent(100L, 900L, LocalDateTime.of(2026, 8, 21, 12, 5))))
                .isInstanceOf(IllegalStateException.class);

        verify(stockApi, never()).confirm(any());
    }

    private OrderCreateRequest request() {
        return new OrderCreateRequest("req-1", List.of(1L, 2L), null, 10L, "문 앞에 놔주세요");
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
