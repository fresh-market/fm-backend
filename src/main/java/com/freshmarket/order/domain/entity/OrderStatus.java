package com.freshmarket.order.domain.entity;

// orders.status CHECK 제약과 같은 값만 둔다.
public enum OrderStatus {
    PAYMENT_PENDING,
    PAID,
    PRODUCT_PREPARING,
    SHIPMENT_PREPARING,
    SHIPPING,
    DELIVERED,
    CONFIRMED,
    RETURN_REQUESTED,
    RETURNED,
    EXCHANGE_REQUESTED,
    EXCHANGED,
    CANCELED
}
