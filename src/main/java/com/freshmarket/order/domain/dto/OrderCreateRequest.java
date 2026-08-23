package com.freshmarket.order.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 주문 생성 요청. cartItemIds(장바구니 주문) 또는 items(바로구매) 중 정확히 하나만 보낸다.
 * requestId는 HTTP 재시도와 중복 클릭을 같은 주문으로 수렴시킨다.
 */
public record OrderCreateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Size(max = 99) List<@NotNull @Positive Long> cartItemIds,
        @Size(max = 99) List<@Valid OrderCreateItemRequest> items,
        @NotNull @Positive Long addressId,
        @Size(max = 255) String shipMessage
) {
}
