package com.freshmarket.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.common.event.OrderPaymentApprovedEvent;
import com.freshmarket.common.event.OrderPaymentFailedEvent;
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
    private OrderPaymentRequestOutboxDispatchService outboxDispatchService;

    private OrderCreateService sut;

    @BeforeEach
    void setUp() {
        sut = new OrderCreateService(
                orderPendingCreationService, orderRepository, orderItemRepository, stockApi, outboxDispatchService);
    }

    @Test
    void 새로_생성된_주문이면_결제요청_outbox를_dispatch한다() {
        OrderCreateRequest request = request();
        OrderCreateResponse response = new OrderCreateResponse(100L, "100", OrderStatus.PAYMENT_PENDING, 38_700);
        when(orderPendingCreationService.createPendingOrder(1L, request))
                .thenReturn(new PendingOrderResult(response, true));

        OrderCreateResponse result = sut.createOrder(1L, request);

        assertThat(result).isEqualTo(response);
        verify(outboxDispatchService).dispatchForOrder(100L);
    }

    @Test
    void requestId_재시도면_미전달_결제요청_outbox를_다시_dispatch한다() {
        OrderCreateRequest request = request();
        OrderCreateResponse response = new OrderCreateResponse(100L, "100", OrderStatus.PAID, 38_700);
        when(orderPendingCreationService.createPendingOrder(1L, request))
                .thenReturn(new PendingOrderResult(response, false));

        OrderCreateResponse result = sut.createOrder(1L, request);

        assertThat(result).isEqualTo(response);
        verify(outboxDispatchService).dispatchForOrder(100L);
    }

    @Test
    void 결제가_승인되면_주문을_PAID로_바꾸고_재고를_확정한다() {
        Order order = order();
        ReflectionTestUtils.setField(order, "id", 100L);
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        OrderItem item1 = orderItem(100L, 501L);
        OrderItem item2 = orderItem(100L, 502L);
        when(orderItemRepository.findAllByOrderIdOrderByIdAsc(100L)).thenReturn(List.of(item1, item2));

        sut.onPaymentApproved(new OrderPaymentApprovedEvent(100L, 900L, LocalDateTime.of(2026, 8, 21, 12, 5)));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(stockApi).confirm(new StockOrderItemsRequest(100L, List.of(501L, 502L)));
    }

    @Test
    void 결제_승인된_주문을_찾을_수_없으면_예외를_던진다() {
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.onPaymentApproved(
                new OrderPaymentApprovedEvent(100L, 900L, LocalDateTime.of(2026, 8, 21, 12, 5))))
                .isInstanceOf(IllegalStateException.class);

        verify(stockApi, never()).confirm(any());
    }

    /*
     * [2026-09-06 KST] PAYMENT_PENDING 만료 배치가 먼저 주문을 CANCELED로 확정한 뒤 뒤늦게 PG 승인이
     * 도착하는 경우다. 재고가 이미 풀려 재배분됐을 수 있어 order를 되돌리면 안 된다 — CANCELED 그대로
     * 두고 markPaid/confirm 둘 다 건드리지 않는지 확인한다.
     */
    @Test
    void 이미_취소된_주문에_뒤늦게_승인이_오면_되돌리지_않는다() {
        Order order = order();
        ReflectionTestUtils.setField(order, "id", 100L);
        order.cancel();
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        sut.onPaymentApproved(new OrderPaymentApprovedEvent(100L, 900L, LocalDateTime.of(2026, 8, 21, 12, 5)));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        verify(stockApi, never()).confirm(any());
        verify(orderItemRepository, never()).findAllByOrderIdOrderByIdAsc(any());
    }

    @Test
    void 결제가_실패하면_주문을_취소하고_재고를_해제한다() {
        Order order = order();
        ReflectionTestUtils.setField(order, "id", 100L);
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        OrderItem item1 = orderItem(100L, 501L);
        OrderItem item2 = orderItem(100L, 502L);
        when(orderItemRepository.findAllByOrderIdOrderByIdAsc(100L)).thenReturn(List.of(item1, item2));

        sut.onPaymentFailed(new OrderPaymentFailedEvent(100L, 900L, "카드 한도 초과"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        verify(stockApi).release(new StockOrderItemsRequest(100L, List.of(501L, 502L)));
    }

    @Test
    void 결제_실패_처리할_주문을_찾을_수_없으면_예외를_던진다() {
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.onPaymentFailed(new OrderPaymentFailedEvent(100L, 900L, "카드 한도 초과")))
                .isInstanceOf(IllegalStateException.class);

        verify(stockApi, never()).release(any());
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
