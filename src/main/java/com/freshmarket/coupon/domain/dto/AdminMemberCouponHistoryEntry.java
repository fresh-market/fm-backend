package com.freshmarket.coupon.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

// 발급분 상태 전이 이력 한 줄
public record AdminMemberCouponHistoryEntry(
        @Schema(description = "이전 상태. 최초 발급이면 null") String fromStatus,
        @Schema(description = "변경된 상태") String toStatus,
        @Schema(description = "사유. 만료와 어뷰징 취소를 가른다") String reason,
        @Schema(description = "처리한 관리자 ID. 배치나 사용자 동작에 의한 자동 전이면 null") Long changedBy,
        @Schema(description = "생성 시각") LocalDateTime createdAt
) {
}
