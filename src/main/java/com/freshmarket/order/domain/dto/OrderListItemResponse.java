package com.freshmarket.order.domain.dto;

import com.freshmarket.order.domain.entity.Order;
import com.freshmarket.order.domain.entity.OrderStatus;
import java.time.LocalDateTime;

public record OrderListItemResponse(
        Long orderId,
        String orderNo,
        OrderStatus status,
        int totalAmount,
        LocalDateTime orderedAt
) {
    public static OrderListItemResponse from(Order order) {
        return new OrderListItemResponse(order.getId(), order.getOrderNo(), order.getStatus(),
                order.getTotalAmount(), order.getOrderedAt());
    }
}
