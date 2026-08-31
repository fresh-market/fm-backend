package com.freshmarket.product.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 관리자가 카테고리를 새로 등록할 때 보내는 요청 본문
public record AdminCategoryCreateRequest(
        @Schema(description = "카테고리 이름", example = "채소") @NotBlank @Size(max = 50) String name,
        @Schema(description = "상위 카테고리 ID. 최상위 카테고리면 생략한다", example = "1") Long parentId
) {
}
