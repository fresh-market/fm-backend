package com.freshmarket.order.domain.dto;

import com.freshmarket.order.domain.entity.Order;
import com.freshmarket.order.domain.entity.OrderStatus;

/** 주문 접수 직후 응답. 결제 및 재고 확정 결과는 후속 상태 조회로 확인한다. */
public record OrderCreateResponse(Long orderId, String orderNo, OrderStatus status, int totalAmount) {

    public static OrderCreateResponse from(Order order) {
        return new OrderCreateResponse(order.getId(), order.getOrderNo(), order.getStatus(), order.getTotalAmount());
    }
}
