package com.freshmarket.stock;

import java.util.List;

// confirm/release 대상 주문상품 id 목록
public record StockOrderItemsRequest(
        Long orderId,
        List<Long> orderItemIds
) {
}
