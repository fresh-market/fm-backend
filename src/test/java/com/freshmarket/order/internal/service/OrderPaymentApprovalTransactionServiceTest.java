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

/*
 * [2026-09-27 KST] OrderCreateServiceTest에 있던 onPaymentApproved()의 DB 로직 테스트를 여기로
 * 옮겼다 — 실제 트랜잭션이 이 서비스로 이동했기 때문이다(OrderPaymentApprovalTransactionService
 * 클래스 주석 참고). OrderExpirationTransactionServiceTest와 같은 구조다.
 */
@ExtendWith(MockitoExtension.class)
class OrderPaymentApprovalTransactionServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private StockApi stockApi;

    private OrderPaymentApprovalTransactionService sut;

    @BeforeEach
    void setUp() {
        sut = new OrderPaymentApprovalTransactionService(orderRepository, orderItemRepository, stockApi);
    }

    @Test
    void 결제가_승인되면_주문을_PAID로_바꾸고_재고를_확정하고_환불이_필요없다고_알린다() {
        Order order = order();
        ReflectionTestUtils.setField(order, "id", 100L);
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        OrderItem item1 = orderItem(100L, 501L);
        OrderItem item2 = orderItem(100L, 502L);
        when(orderItemRepository.findAllByOrderIdOrderByIdAsc(100L)).thenReturn(List.of(item1, item2));

        boolean refundNeeded = sut.applyApproval(100L, 900L);

        assertThat(refundNeeded).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(stockApi).confirm(new StockOrderItemsRequest(100L, List.of(501L, 502L)));
    }

    @Test
    void 결제_승인된_주문을_찾을_수_없으면_예외를_던진다() {
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.applyApproval(100L, 900L))
                .isInstanceOf(IllegalStateException.class);

        verify(stockApi, never()).confirm(any());
    }

    /*
     * [2026-09-06 KST] PAYMENT_PENDING 만료 배치가 먼저 주문을 CANCELED로 확정한 뒤 뒤늦게 PG 승인이
     * 도착하는 경우다. 재고가 이미 풀려 재배분됐을 수 있어 order를 되돌리면 안 된다 — CANCELED 그대로
     * 두고 markPaid/confirm 둘 다 건드리지 않는지 확인한다.
     *
     * [2026-09-27 KST] 이제는 여기서 끝나지 않고 true(환불 필요)를 돌려준다 — 자동 환불을 위해
     * 호출하는 쪽(OrderCreateService.onPaymentApproved)이 OrderPaymentRefundRequestedEvent를
     * 발행하게 한다.
     */
    @Test
    void 이미_취소된_주문에_뒤늦게_승인이_오면_되돌리지_않고_환불이_필요하다고_알린다() {
        Order order = order();
        ReflectionTestUtils.setField(order, "id", 100L);
        order.cancel();
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        boolean refundNeeded = sut.applyApproval(100L, 900L);

        assertThat(refundNeeded).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        verify(stockApi, never()).confirm(any());
        verify(orderItemRepository, never()).findAllByOrderIdOrderByIdAsc(any());
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
