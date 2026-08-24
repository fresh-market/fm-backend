package com.freshmarket.stock;

import java.util.List;

// 재고 예약 요청. 주문 하나에 속한 주문상품들을 한 번에 예약한다(전체 성공 또는 전체 롤백)
public record StockReservationRequest(
        Long orderId,
        List<StockReservationItemRequest> items
) {
}
