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
        // QueryDSL이 @QueryProjection 생성자를 찾을 수 있게 record의 canonical 생성자에 어노테이션만 붙인다
    }
}
