package com.freshmarket.stock;

// 예약 대상 주문상품 한 줄
public record StockReservationItemRequest(
        Long orderItemId,
        Long productOptionId,
        int qty
) {
}
