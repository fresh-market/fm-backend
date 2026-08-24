package com.freshmarket.order.domain.dto;

import com.freshmarket.order.domain.entity.OrderItem;
import com.freshmarket.order.domain.entity.OrderItemStatus;

public record OrderItemResponse(
        Long orderItemId,
        Long productOptionId,
        String nameSnapshot,
        String optionNameSnapshot,
        int unitPrice,
        int qty,
        int discountAmount,
        int lineAmount,
        OrderItemStatus itemStatus
) {
    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(item.getId(), item.getProductOptionId(), item.getNameSnapshot(),
                item.getOptionNameSnapshot(), item.getUnitPrice(), item.getQty(),
                item.getDiscountAmount(), item.getLineAmount(), item.getItemStatus());
    }
}
