package com.freshmarket.product.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminProductOptionCreateRequest(
        @Schema(description = "옵션명", example = "1kg") @NotBlank @Size(max = 100) String name,
        @Schema(description = "옵션 판매가", example = "12900") @NotNull @Min(0) Integer price
) {
}
