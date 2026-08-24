package com.freshmarket.stock.domain.dto;

import com.freshmarket.product.ProductOptionInfo;
import io.swagger.v3.oas.annotations.media.Schema;

/*
 * 소비기한 임박 상품 응답. stock.md 원칙("회원에게는 수치를 노출하지 않는다")에 따라
 * 정확한 잔여 수량/일수는 담지 않고, 상품 식별 정보만 담는다.
 */
public record ExpiringSoonResponse(
        @Schema(description = "상품 ID", example = "12") Long productId,
        @Schema(description = "상품명", example = "제주 감귤 1kg") String productName,
        @Schema(description = "옵션 ID", example = "31") Long productOptionId,
        @Schema(description = "옵션명", example = "1kg") String optionName,
        @Schema(description = "가격(원)", example = "12900") int priceKrw
) {
    public static ExpiringSoonResponse from(ProductOptionInfo info) {
        return new ExpiringSoonResponse(
                info.productId(), info.productName(), info.productOptionId(),
                info.optionName(), info.price());
    }
}