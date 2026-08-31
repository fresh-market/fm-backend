package com.freshmarket.product.internal.dto;

import com.freshmarket.product.internal.entity.SaleStatus;
import com.querydsl.core.annotations.QueryProjection;
import java.time.LocalDateTime;

// 관리자 상품 목록 쿼리 전용 프로젝션. 응답 조립(카테고리명 배치 조회)은 서비스가 한다
public record AdminProductListRow(
        Long productId,
        String productCode,
        String name,
        Long categoryId,
        SaleStatus saleStatus,
        boolean deleted,
        // 커서 페이지네이션 정렬 기준값(API-3-04). 응답에는 안 실리고 다음 페이지 토큰 계산에만 쓴다
        LocalDateTime createdAt
) {

    @QueryProjection
    public AdminProductListRow {
        // QueryDSL이 @QueryProjection 생성자를 찾을 수 있게 record의 canonical 생성자에 어노테이션만 붙인다
    }
}
