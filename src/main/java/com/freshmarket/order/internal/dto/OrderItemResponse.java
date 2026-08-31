package com.freshmarket.order.internal.dto;

import com.freshmarket.order.internal.entity.OrderItem;
import com.freshmarket.order.internal.entity.OrderItemStatus;

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
