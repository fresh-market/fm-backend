package com.freshmarket.product.internal.dto;

import com.freshmarket.product.internal.entity.SaleStatus;
import io.swagger.v3.oas.annotations.media.Schema;

// 관리자 상품 목록의 항목 하나. 판매안함/품절/삭제 상품도 포함해서 보여준다
public record AdminProductListItem(
        @Schema(description = "상품 ID", example = "12") Long productId,
        @Schema(description = "상품 코드", example = "P-2026-7K3QXZ") String productCode,
        @Schema(description = "상품명", example = "제주 감귤 1kg") String name,
        @Schema(description = "소속 카테고리 요약") CategorySummary category,
        @Schema(description = "상품 판매 상태", example = "ON_SALE") SaleStatus saleStatus,
        @Schema(description = "삭제 여부", example = "false") boolean deleted
) {
}
