package com.freshmarket.product.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 관리자가 카테고리 이름을 바꿀 때 보내는 요청 본문
public record AdminCategoryUpdateRequest(
        @Schema(description = "바꿀 카테고리 이름", example = "과일") @NotBlank @Size(max = 50) String name
) {
}
