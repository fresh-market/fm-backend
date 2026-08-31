package com.freshmarket.product.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// 상품 목록과 상세가 함께 쓰는 최소 카테고리 정보
public record CategorySummary(
        @Schema(description = "카테고리 ID", example = "4") Long categoryId,
        @Schema(description = "카테고리 이름", example = "과일") String name
) {
}