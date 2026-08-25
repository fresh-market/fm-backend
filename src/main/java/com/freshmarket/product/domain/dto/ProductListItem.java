package com.freshmarket.product.domain.dto;

import com.freshmarket.product.domain.entity.SaleStatus;
import io.swagger.v3.oas.annotations.media.Schema;

// 상품 목록의 항목 하나
public record ProductListItem(
        @Schema(description = "상품 ID", example = "12") Long productId,
        @Schema(description = "상품명", example = "제주 감귤 1kg") String name,
        @Schema(description = "소속 카테고리 요약") CategorySummary category,
        @Schema(description = "옵션 최저가(원). 가격 필터가 걸린 경우 조건을 만족하는 옵션 중 최저가다",
                example = "12900") Integer minPriceKrw,
        @Schema(description = "상품 판매 상태", example = "ON_SALE") SaleStatus saleStatus,
        @Schema(description = "품절 여부. 소속 옵션이 전부 품절이면 true다",
                example = "false") boolean soldOut,
        @Schema(description = "대표 이미지 URL. CDN 설정 전이라 현재는 항상 null이다")
        String mainImageUrl
) {
}