package com.freshmarket.product.internal.dto;

import com.freshmarket.product.internal.entity.Category;
import io.swagger.v3.oas.annotations.media.Schema;

// 카테고리 조회/등록/수정 응답에 공통으로 쓰는 응답 본문
public record CategoryResponse(
        @Schema(description = "카테고리 ID", example = "1") Long id,
        @Schema(description = "카테고리 이름", example = "채소") String name,
        @Schema(description = "상위 카테고리 ID. 최상위 카테고리면 null이다", example = "null") Long parentId
) {

    // Category 엔티티를 응답 형태로 변환한다
    public static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getParentId());
    }
}