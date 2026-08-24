package com.freshmarket.product.domain.dto;

import com.freshmarket.product.domain.entity.SaleStatus;
import com.querydsl.core.annotations.QueryProjection;

// 관리자 상품 목록 쿼리 전용 프로젝션. 응답 조립(카테고리명 배치 조회)은 서비스가 한다
public record AdminProductListRow(
        Long productId,
        String productCode,
        String name,
        Long categoryId,
        SaleStatus saleStatus,
        boolean deleted
) {

    @QueryProjection
    public AdminProductListRow {
    }
}
