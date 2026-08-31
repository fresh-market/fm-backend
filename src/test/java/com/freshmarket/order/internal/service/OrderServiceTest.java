package com.freshmarket.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.order.internal.dto.OrderDetailResponse;
import com.freshmarket.order.internal.dto.OrderSearchCondition;
import com.freshmarket.order.internal.entity.Order;
import com.freshmarket.order.internal.entity.OrderItem;
import com.freshmarket.order.internal.entity.OrderItemStatus;
import com.freshmarket.order.internal.entity.OrderStatus;
import com.freshmarket.order.internal.exception.OrderErrorCode;
import com.freshmarket.order.internal.exception.OrderException;
import com.freshmarket.order.internal.repository.OrderItemRepository;
import com.freshmarket.order.internal.repository.OrderQueryRepository;
import com.freshmarket.order.internal.repository.OrderRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderQueryRepository orderQueryRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    private OrderService sut;

    @BeforeEach
    void setUp() {
        sut = new OrderService(orderRepository, orderQueryRepository, orderItemRepository);
    }

    @Test
    void 내_주문을_상태와_기간으로_필터링해_조회한다() {
        Pageable pageable = PageRequest.of(0, 20);
        Order order = order();
        when(orderQueryRepository.findAllByMemberIdAndCondition(eq(1L), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(order), pageable, 1));

        Page<Order> result = sut.getOrders(1L,
                new OrderSearchCondition(OrderStatus.PAID, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)),
                pageable);

        assertThat(result.getContent()).containsExactly(order);
        verify(orderQueryRepository).findAllByMemberIdAndCondition(eq(1L),
                eq(new OrderSearchCondition(OrderStatus.PAID, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31))),
                eq(pageable));
    }

    @Test
    void 시작일이_종료일보다_늦으면_조회하지_않는다() {
        assertThatThrownBy(() -> sut.getOrders(1L,
                new OrderSearchCondition(null, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 8, 31)),
                PageRequest.of(0, 20)))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(OrderErrorCode.INVALID_ORDER_PERIOD);

        verify(orderQueryRepository, never()).findAllByMemberIdAndCondition(any(), any(), any());
    }

    @Test
    void 내_주문_상세는_주문시점_상품과_배송지_스냅샷으로_반환한다() {
        Order order = order();
        ReflectionTestUtils.setField(order, "id", 100L);
        OrderItem item = OrderItem.place(100L, 31L, "제주 감귤 1kg", "1kg", 12900, 2,
                1L, null, null, 0, 1000, OrderItemStatus.ORDERED);
        ReflectionTestUtils.setField(item, "id", 501L);
        when(orderRepository.findByIdAndMemberId(100L, 1L)).thenReturn(Optional.of(order));
        when(orderItemRepository.findAllByOrderIdOrderByIdAsc(100L)).thenReturn(List.of(item));

        OrderDetailResponse result = sut.getOrder(1L, 100L);

        assertThat(result.orderNo()).isEqualTo("20260821-000001");
        assertThat(result.shippingAddress().recipient()).isEqualTo("홍길동");
        assertThat(result.items()).singleElement().satisfies(response -> {
            assertThat(response.nameSnapshot()).isEqualTo("제주 감귤 1kg");
            assertThat(response.lineAmount()).isEqualTo(24800);
        });
    }

    @Test
    void 존재하지_않거나_내_소유가_아닌_주문은_찾을수없음으로_처리한다() {
        when(orderRepository.findByIdAndMemberId(100L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.getOrder(1L, 100L))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);

        verify(orderItemRepository, never()).findAllByOrderIdOrderByIdAsc(any());
    }

    private Order order() {
        return Order.place(1L, "20260821-000001", 25800, 1000,
                null, null, 0, 3000, 27800, "홍길동", "01012345678", "06234",
                "서울 강남구 테헤란로 1", "부재 시 경비실", LocalDateTime.of(2026, 8, 21, 12, 0));
    }
}
