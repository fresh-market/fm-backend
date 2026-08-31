package com.freshmarket.order.internal.dto;

import com.freshmarket.order.internal.entity.Order;
import com.freshmarket.order.internal.entity.OrderStatus;
import java.time.LocalDateTime;
import java.util.List;

public record OrderDetailResponse(
        Long orderId,
        String orderNo,
        OrderStatus status,
        int productAmount,
        int discountAmount,
        int shippingFee,
        int totalAmount,
        LocalDateTime orderedAt,
        ShippingAddressResponse shippingAddress,
        String shipMessage,
        List<OrderItemResponse> items
) {
    public static OrderDetailResponse from(Order order, List<OrderItemResponse> items) {
        return new OrderDetailResponse(order.getId(), order.getOrderNo(), order.getStatus(),
                order.getProductAmount(), order.getDiscountAmount(), order.getShippingFee(),
                order.getTotalAmount(), order.getOrderedAt(), ShippingAddressResponse.from(order),
                order.getShipMessage(), items);
    }
}
