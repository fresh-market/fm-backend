package com.freshmarket.cart.domain.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CartItemCreateRequest(
        @NotNull Long productOptionId,
        @Min(1) int qty
) {
}
