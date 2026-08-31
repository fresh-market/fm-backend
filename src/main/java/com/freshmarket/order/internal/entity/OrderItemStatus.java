package com.freshmarket.order.internal.entity;

// order_item.item_status CHECK 제약과 같은 값만 둔다.
public enum OrderItemStatus {
    ORDERED,
    CANCELED,
    RETURN_REQ,
    RETURNED,
    EXCHANGE_REQ,
    EXCHANGED
}
