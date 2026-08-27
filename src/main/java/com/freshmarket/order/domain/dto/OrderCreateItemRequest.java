package com.freshmarket.order.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 바로구매할 상품 옵션과 수량이다. 가격·상품명은 서버가 상품 도메인에서 다시 조회한다. */
public record OrderCreateItemRequest(
        @NotNull @Positive Long productOptionId,
        @Positive int qty
) {
}
