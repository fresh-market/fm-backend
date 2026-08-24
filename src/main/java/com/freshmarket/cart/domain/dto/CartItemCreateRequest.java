package com.freshmarket.cart.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CartItemCreateRequest(
        @NotNull Long productOptionId,
        // 상한이 없으면 Integer.MAX_VALUE 근처 값을 보내
        // ArithmeticException(처리되지 않아 500)을 던지게 만들 수 있다
        @Min(1) @Max(999) int qty
) {
}
