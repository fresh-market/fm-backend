package com.freshmarket.coupon.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record AdminMemberCouponHistoryResponse(
        @Schema(description = "이 발급분이 거친 상태 전이를 일어난 순서대로 담은 목록") List<AdminMemberCouponHistoryEntry> history
) {
}
