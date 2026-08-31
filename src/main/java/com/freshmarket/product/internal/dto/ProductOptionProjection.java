package com.freshmarket.product.internal.dto;

import com.querydsl.core.annotations.QueryProjection;

/*
 * ProductApiImpl 내부에서만 쓰는 QueryDSL 프로젝션.
 * 공개 계약인 ProductOptionInfo(최상위 패키지)에는 QueryDSL 애노테이션을 안 넣기 위해 분리했다.
 */
public record ProductOptionProjection(
        Long productId,
        Long categoryId,
        Long productOptionId,
        String productName,
        String optionName,
        int price,
        boolean purchasable,
        int saleAvailableDaysFromExpiry
) {
    @QueryProjection
    public ProductOptionProjection {
    }
}