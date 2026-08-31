package com.freshmarket.product.internal.dto;

import com.freshmarket.product.internal.entity.ProductOption;
import io.swagger.v3.oas.annotations.media.Schema;

// 상품 등록 응답에 포함되는 옵션 하나
public record AdminProductOptionResponse(
        @Schema(description = "옵션 ID", example = "31") Long productOptionId,
        @Schema(description = "옵션명", example = "1kg") String name,
        @Schema(description = "옵션 판매가", example = "12900") int price
) {

    // ProductOption 엔티티를 응답 형태로 변환한다
    public static AdminProductOptionResponse from(ProductOption option) {
        return new AdminProductOptionResponse(option.getId(), option.getName(), option.getPrice());
    }
}
