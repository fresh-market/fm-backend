package com.freshmarket.product.internal.dto;

import com.freshmarket.product.internal.entity.SaleStatus;
import io.swagger.v3.oas.annotations.media.Schema;

// 상품 상세에 딸려 나가는 옵션 하나. 판매 단위(SKU)이며 가격이 여기 붙는다
public record ProductOptionResponse(
        @Schema(description = "옵션 ID", example = "31") Long productOptionId,
        @Schema(description = "옵션명", example = "1kg") String name,
        @Schema(description = "옵션 판매가(원)", example = "12900") int priceKrw,
        @Schema(description = "옵션 판매 상태", example = "ON_SALE") SaleStatus saleStatus,
        @Schema(description = "품절 여부", example = "false") boolean soldOut
) {
}