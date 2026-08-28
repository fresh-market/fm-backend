package com.freshmarket.product.domain.dto;

import com.freshmarket.product.domain.entity.ProductImage;
import io.swagger.v3.oas.annotations.media.Schema;

// 업로드 확정 응답. 백엔드공통_이미지저장소_설계.md 6.2절의 확정 응답 형태({"imageId": ..., "sortOrder": ...})를 따른다
public record AdminProductImageConfirmResponse(
        @Schema(description = "이미지 ID", example = "88") Long productImageId,
        @Schema(description = "표시 순서", example = "0") int sortOrder
) {

    public static AdminProductImageConfirmResponse of(ProductImage image) {
        return new AdminProductImageConfirmResponse(image.getId(), image.getSortOrder());
    }
}
