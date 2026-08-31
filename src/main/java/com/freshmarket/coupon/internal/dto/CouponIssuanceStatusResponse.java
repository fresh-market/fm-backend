package com.freshmarket.coupon.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// 선착순 쿠폰의 발급 현황. 진행 중인 이벤트에서는 issuedQuantity가 Redis 카운터의 실시간 값이다
public record CouponIssuanceStatusResponse(
        @Schema(description = "한정 수량", example = "10000") int totalQuantity,
        @Schema(description = "지금까지 나간 발급 수", example = "8231") int issuedQuantity,
        @Schema(description = "남은 수량", example = "1769") int remaining
) {
}
