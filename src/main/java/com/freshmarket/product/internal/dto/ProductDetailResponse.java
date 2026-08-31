package com.freshmarket.product.internal.dto;

import com.freshmarket.product.internal.entity.SaleStatus;
import com.freshmarket.product.internal.entity.StorageType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// 상품 상세 조회 응답 본문
public record ProductDetailResponse(
        @Schema(description = "상품 ID", example = "12") Long productId,
        @Schema(description = "상품 코드", example = "P-2026-0012") String productCode,
        @Schema(description = "상품명", example = "제주 감귤 1kg") String name,
        @Schema(description = "상품 설명. 없으면 null이다") String description,
        @Schema(description = "소속 카테고리 요약") CategorySummary category,
        @Schema(description = "보관 온도", example = "COLD") StorageType storageType,
        @Schema(description = "상품 판매 상태", example = "ON_SALE") SaleStatus saleStatus,
        @Schema(description = "판매 가능한 옵션 목록. 판매안함(OFF_SALE) 옵션은 제외된다")
        List<ProductOptionResponse> options,
        @Schema(description = "확정된 이미지 목록. 업로드가 끝나지 않은 이미지는 제외된다")
        List<ProductDetailImageResponse> images,
        @Schema(description = "리뷰 요약") ReviewSummaryResponse review
) {
}