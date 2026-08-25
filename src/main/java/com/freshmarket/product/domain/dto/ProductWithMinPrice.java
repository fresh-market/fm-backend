package com.freshmarket.product.domain.dto;

import com.querydsl.core.annotations.QueryProjection;
import com.freshmarket.product.domain.entity.SaleStatus;
import java.time.LocalDateTime;

public record ProductWithMinPrice(
        Long productId,
        String name,
        Long categoryId,
        String categoryName,
        Integer minPrice,
        SaleStatus saleStatus,
        // 조인된 옵션(OFF_SALE 제외)이 전부 품절이어야 true다. 하나라도 살아있으면 구매 가능하므로 false
        boolean soldOut,
        LocalDateTime createdAt
) {

    @QueryProjection
    public ProductWithMinPrice {
    }
}