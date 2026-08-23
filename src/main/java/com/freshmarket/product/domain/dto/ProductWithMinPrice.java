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
        LocalDateTime createdAt
) {

    @QueryProjection
    public ProductWithMinPrice {
    }
}