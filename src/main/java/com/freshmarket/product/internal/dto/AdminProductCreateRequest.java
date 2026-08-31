package com.freshmarket.product.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

// 관리자가 상품을 새로 등록할 때 보내는 요청 본문. 재고와 소비기한은 로트 입고로만 들어오므로 여기서 받지 않는다
public record AdminProductCreateRequest(
        @Schema(description = "상품명", example = "제주 감귤 1kg") @NotBlank @Size(max = 255) String name,
        @Schema(description = "요청 식별자(클라이언트가 생성). 같은 값으로 재시도하면 최초 등록 결과를 그대로 돌려준다",
                example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") @NotBlank @Size(max = 100) String requestId,
        @Schema(description = "카테고리 ID", example = "4") @NotNull @Positive Long categoryId,
        @Schema(description = "공급처 ID", example = "2") @NotNull @Positive Long supplierId,
        @Schema(description = "보관 온도", example = "COLD") @NotBlank @Pattern(regexp = "ROOM|COLD|FROZEN") String storageType,
        @Schema(description = "판매 가능 최소 잔여일수. 생략하면 0", example = "3") @Min(0) Integer saleAvailableDaysFromExpiry,
        @Schema(description = "상품 설명", example = "달콤한 제주 감귤입니다.") @Size(max = 2000) String description,
        @Schema(description = "판매 옵션 목록. 최소 1개, 최대 50개") @NotEmpty @Size(max = 50) @Valid List<AdminProductOptionCreateRequest> options
) {
}
