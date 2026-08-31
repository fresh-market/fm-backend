package com.freshmarket.product.internal.dto;

import com.freshmarket.product.internal.entity.Product;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// 상품 등록 응답. product.md에 등록 응답 형태가 명시돼 있지 않아, 엔티티 필드를 그대로 반영해 구성했다(카테고리 등록 응답과 같은 방식)
public record AdminProductResponse(
        @Schema(description = "상품 ID", example = "12") Long productId,
        @Schema(description = "요청 식별자", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") String requestId,
        @Schema(description = "자동생성 상품코드", example = "P-2026-7K3QXZ") String productCode,
        @Schema(description = "상품명", example = "제주 감귤 1kg") String name,
        @Schema(description = "카테고리 ID", example = "4") Long categoryId,
        @Schema(description = "공급처 ID", example = "2") Long supplierId,
        @Schema(description = "보관 온도", example = "COLD") String storageType,
        @Schema(description = "판매 가능 최소 잔여일수", example = "3") int saleAvailableDaysFromExpiry,
        @Schema(description = "상품 설명") String description,
        @Schema(description = "판매 옵션 목록") List<AdminProductOptionResponse> options
) {

    // Product 엔티티와 옵션 응답 목록을 합쳐 등록 응답을 만든다
    public static AdminProductResponse of(Product product, List<AdminProductOptionResponse> options) {
        return new AdminProductResponse(
                product.getId(), product.getRequestId(), product.getProductCode(), product.getName(),
                product.getCategoryId(), product.getSupplierId(), product.getStorageType().name(),
                product.getSaleAvailableDaysFromExpiry(), product.getDescription(), options);
    }
}
