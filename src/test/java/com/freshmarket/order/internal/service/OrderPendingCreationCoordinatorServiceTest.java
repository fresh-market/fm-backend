package com.freshmarket.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.freshmarket.order.internal.PendingOrderResult;
import com.freshmarket.order.internal.dto.OrderCreateRequest;
import com.freshmarket.order.internal.dto.OrderCreateResponse;
import com.freshmarket.order.internal.entity.OrderStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class OrderPendingCreationCoordinatorServiceTest {

    @Mock
    private OrderPendingCreationService orderPendingCreationService;

    @Test
    void 동시_requestId_유니크_충돌이면_기존_주문_응답으로_수렴한다() {
        OrderCreateRequest request = new OrderCreateRequest("request-1", List.of(1L), null, 10L, null);
        PendingOrderResult existing = new PendingOrderResult(
                new OrderCreateResponse(100L, "100", OrderStatus.PAYMENT_PENDING, 25_800), false);
        DataIntegrityViolationException conflict = new DataIntegrityViolationException(
                "Duplicate entry 'request-1' for key 'uk_orders_request_id'");
        when(orderPendingCreationService.createPendingOrder(1L, request)).thenThrow(conflict);
        when(orderPendingCreationService.findExistingOrderResult(1L, request))
                .thenReturn(Optional.of(existing));
        OrderPendingCreationCoordinatorService sut = new OrderPendingCreationCoordinatorService(
                orderPendingCreationService);

        PendingOrderResult result = sut.createPendingOrder(1L, request);

        assertThat(result).isEqualTo(existing);
    }

    @Test
    void requestId_이외의_무결성_위반은_그대로_전파한다() {
        OrderCreateRequest request = new OrderCreateRequest("request-1", List.of(1L), null, 10L, null);
        DataIntegrityViolationException conflict = new DataIntegrityViolationException(
                "Duplicate entry '1' for key 'uk_orders_order_no'");
        when(orderPendingCreationService.createPendingOrder(1L, request)).thenThrow(conflict);
        OrderPendingCreationCoordinatorService sut = new OrderPendingCreationCoordinatorService(
                orderPendingCreationService);

        assertThatThrownBy(() -> sut.createPendingOrder(1L, request)).isSameAs(conflict);
    }

    @Test
    void requestId_유니크_충돌_뒤_기존_주문을_찾지_못하면_실패한다() {
        OrderCreateRequest request = new OrderCreateRequest("request-1", List.of(1L), null, 10L, null);
        DataIntegrityViolationException conflict = new DataIntegrityViolationException(
                "Duplicate entry 'request-1' for key 'uk_orders_request_id'");
        when(orderPendingCreationService.createPendingOrder(1L, request)).thenThrow(conflict);
        when(orderPendingCreationService.findExistingOrderResult(1L, request)).thenReturn(Optional.empty());
        OrderPendingCreationCoordinatorService sut = new OrderPendingCreationCoordinatorService(
                orderPendingCreationService);

        assertThatThrownBy(() -> sut.createPendingOrder(1L, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("request_id 유니크 위반 직후 기존 주문을 찾을 수 없습니다");
    }
}
